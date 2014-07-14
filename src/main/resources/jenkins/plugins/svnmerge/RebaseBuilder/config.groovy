import lib.FormTagLib

def f = namespace(FormTagLib.class)

f.entry(title:_("Build to rebase to"), field:"permalink") {
    f.select()
}

f.entry(title:_("Record merge only"), field:"recordOnly") {
    f.checkbox()
}
