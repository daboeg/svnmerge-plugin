package jenkins.plugins.svnmerge;

import hudson.model.PermalinkProjectAction.Permalink;

/**
 * @author Kohsuke Kawaguchi
 */
public class RebaseSetting {
    /**
     * Revision to rebase with. -1 to rebase to the latest;
     */
    public final long revision;
    
    /**
     * Record the merge only during rebase.
     */
    public final boolean recordOnly;

    /**
     * Permalink ID of the upstream to rebase to.
     * If this value is non-null, it takes precedence over {@link #revision}
     */
    public final String permalink;

    public RebaseSetting(long revision, boolean recordOnly) {
        this.revision = revision;
        this.permalink = null;
        this.recordOnly = recordOnly;
    }

    public RebaseSetting(String permalink, boolean recordOnly) {
        this.revision = -1;
        this.permalink = permalink;
        this.recordOnly = recordOnly;
    }

    public RebaseSetting(Permalink p, boolean recordOnly) {
        this(p.getId(), recordOnly);
    }
}
