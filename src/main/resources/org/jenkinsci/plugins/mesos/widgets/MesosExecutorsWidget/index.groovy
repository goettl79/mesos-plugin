package org.jenkinsci.plugins.mesos.widgets.MesosExecutorsWidget

def m = namespace(lib.MesosTagLib.class)

m.executors(computers:view.computers, it:view)
