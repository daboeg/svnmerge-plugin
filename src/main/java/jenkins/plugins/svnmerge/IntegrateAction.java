package jenkins.plugins.svnmerge;

import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildBadgeAction;
import hudson.model.Cause;
import hudson.model.Fingerprint;
import hudson.model.Fingerprint.RangeSet;
import hudson.model.Queue.Task;
import hudson.model.TaskListener;
import hudson.scm.ChangeLogSet.Entry;
import hudson.scm.SubversionChangeLogSet.LogEntry;
import hudson.scm.SubversionSCM.SvnInfo;
import hudson.scm.SubversionTagAction;
import hudson.security.ACL;
import jenkins.model.Jenkins;
import jenkins.plugins.svnmerge.FeatureBranchProperty.IntegrationResult;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

/**
 * {@link AbstractBuild}-level action to integrate
 * the build to upstream branch.
 *
 * @author Kohsuke Kawaguchi
 */
public class IntegrateAction extends AbstractSvnmergeTaskAction<IntegrateSetting> implements BuildBadgeAction {
    public final AbstractBuild<?,?> build;

    /**
     * If the integration is successful, set to the revision of the commit of the merge.
     * If the integration is successful but there was nothing to merge, 0.
     * If it failed, -1. If an integration was never attempted, null.
     */
    private Long integratedRevision;

    /**
     * Commit in the branch that was merged into the {@link #integratedRevision}
     */
    private Long integrationSource;
    
    public IntegrateAction(AbstractBuild<?,?> build) {
        this.build = build;
    }

    public String getIconFileName() {
        if(!isApplicable()) return null; // missing configuration
        return "/plugin/svnmerge/24x24/integrate.gif";
    }

    public String getDisplayName() {
        return "Integrate Branch";
    }

    public String getUrlName() {
        return "integrate-branch";
    }

    @Override
    public AbstractProject<?, ?> getProject() {
        return build.getProject();
    }

    protected ACL getACL() {
        return build.getACL();
    }

    /**
     * Do we have enough information to perform integration?
     * If not, we need to pretend as if this action is not here.
     */
    private boolean isApplicable() {
        return getSvnInfo()!=null && getProperty()!=null;
    }

    public boolean isIntegrated() {
        return integratedRevision!=null && integratedRevision>0;
    }

    public boolean isIntegrationAttempted() {
        return integratedRevision!=null;
    }

    public Long getIntegratedRevision() {
        return integratedRevision;
    }

    public Long getIntegrationSource() {
        return integrationSource;
    }

    public File getLogFile() {
        return new File(build.getRootDir(),"integrate.log");
    }

    @Override
    protected TaskImpl createTask(IntegrateSetting s) throws IOException {
        return new IntegrationTask(s);
    }

    @Override
    protected IntegrateSetting createParams(StaplerRequest req) throws IOException {
        return new IntegrateSetting();
    }

    /**
     * URL and revision to be integrated from this action.
     */
    public SvnInfo getSvnInfo() {
        SubversionTagAction sta = build.getAction(SubversionTagAction.class);
        if(sta==null)   return null;
        Map<SvnInfo,List<String>> tags = sta.getTags();
        if(tags.size()!=1)  return null;    // can't handle more than 1 URLs
        return tags.keySet().iterator().next();
    }

    /**
     * Integrate the branch.
     * <p>
     * This requires that the calling thread owns the workspace.
     */
    /*package*/ long perform(TaskListener listener, IntegrateSetting _) throws IOException, InterruptedException {
        return perform(listener, getSvnInfo());
    }

    /**
     * @param src
     *      We are taking this revision and merge it into the upstream.
     */
    public long perform(TaskListener listener, SvnInfo src) throws IOException, InterruptedException {
        String commitMessage = getCommitMessage();

        // if this is -1, it doesn't capture
        IntegrationResult r = getProperty().integrate(listener, src.url, -1, commitMessage);
        integratedRevision = r.mergeCommit;
        integrationSource = r.integrationSource;
        if(integratedRevision>0) {
            // record this integration as a fingerprint.
            // this will allow us to find where this change is integrated.
            Jenkins.getInstance().getFingerprintMap().getOrCreate(
                    build, IntegrateAction.class.getName(),
                    getFingerprintKey());
        }
        build.save();
        return integratedRevision;
    }

    /**
     * Gets the build number of the upstream where this integration is built.
     *
     * <p>
     * Since the relevant information might be already lost when this method
     * is called, this code needs to be defensive.
     *
     * @return -1
     *      if not integrated yet or this information is lost.
     */
    public int getUpstreamBuildNumber() throws IOException {
        Fingerprint f = Jenkins.getInstance().getFingerprintMap().get(getFingerprintKey());
        if(f==null)         return -1;
        FeatureBranchProperty p = getProperty();
        RangeSet rs = new RangeSet(); // empty range set
        if(p!=null)
            rs = f.getRangeSet(p.getUpstreamProject());
        else {
            // we don't know for sure what is our upstream project.
            Hashtable<String,RangeSet> usages = f.getUsages();
            if(!usages.isEmpty())
                rs = usages.values().iterator().next();
        }
        if(rs.isEmpty())    return -1;

        return rs.min();
    }

    /**
     * This is the md5 hash to keep track of where this change is integrated.
     */
    public String getFingerprintKey() {
        return Util.getDigestOf(getCommitMessage()+"#"+integratedRevision);
    }

    private String getCommitMessage() {
        StringBuilder message = new StringBuilder();
        message.append(COMMIT_MESSAGE_PREFIX + build.getFullDisplayName()+ COMMIT_MESSAGE_SUFFIX);
        message.append("\n\nChange Set:\n");
        for (Entry change: build.getChangeSet()) {
            message.append(change.getAuthor().getId());
            message.append(" (rev ");
            message.append(change.getCommitId());
            message.append(") - ");
            message.append(change.getMsg());
            message.append("\n");
        }
        return message.toString();
    }

    /**
     * Cancels an integration task in the queue, if any.
     */
    public void doCancelQueue(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        build.getProject().checkPermission(AbstractProject.BUILD);
        Jenkins.getInstance().getQueue().cancel(new IntegrationTask(null));
        rsp.forwardToPreviousPage(req);
    }

    /**
     * Which page to render?
     */
    protected String decidePage() {
        if(isIntegrated())          return "completed.jelly";
        if (workerThread != null)   return "inProgress.jelly";
        return "form.jelly";
    }

    /**
     * {@link Task} that performs the integration.
     */
    private class IntegrationTask extends TaskImpl {
        private IntegrationTask(IntegrateSetting s) throws IOException {
            super(s);
        }

        public String getFullDisplayName() {
            return Messages.IntegrateAction_DisplayName(getProject().getFullDisplayName());
        }

        public String getDisplayName() {
            return Messages.IntegrateAction_DisplayName(getProject().getDisplayName());
        }
    }

    /**
     * Checks if the given {@link Entry} represents a commit from
     * {@linkplain #perform(TaskListener,IntegrateSetting) integration}. If so,
     * return its fingerprint.
     *
     * Otherwise null.
     */
    public static Fingerprint getIntegrationFingerprint(Entry changeEntry) throws IOException {
        if (changeEntry instanceof LogEntry) {
            LogEntry le = (LogEntry) changeEntry;
            String msg = changeEntry.getMsg().trim();
            if(msg.startsWith(COMMIT_MESSAGE_PREFIX) && msg.contains(COMMIT_MESSAGE_SUFFIX + "\n")) {
                String s = msg.substring(0, msg.indexOf(COMMIT_MESSAGE_SUFFIX) + COMMIT_MESSAGE_SUFFIX.length());
                // this build is merging an integration. Leave this in the record
                return Jenkins.getInstance().getFingerprintMap().get(Util.getDigestOf(s + "#" + le.getRevision()));
            }
        }
        return null;
    }

    // used to find integration commits. commit messages start with PREFIX, contains SUFFIX, followed by paths
    static final String COMMIT_MESSAGE_PREFIX = "Integrated ";
    static final String COMMIT_MESSAGE_SUFFIX = " (from Jenkins)";
}
