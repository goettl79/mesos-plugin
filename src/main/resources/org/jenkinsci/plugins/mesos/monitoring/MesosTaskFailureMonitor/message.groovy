package org.jenkinsci.plugins.mesos.monitoring.MesosTaskFailureMonitor

def f = namespace(lib.FormTagLib)

if (my.isFixingActive()) {
    div(class:"info") {
        raw _("inProgress",my.url)
    }
} else if (my.logFile.exists()) {
    form(method:"POST",action:"${my.url}/dismiss",name:"dismissFailedTasks") {
        raw _("completed",my.url)
        f.submit(name:"dismiss",value:_("Dismiss this message"))
    }
}

if (!my.failedSlaves.isEmpty()) {
    form(method:"POST",action:"${my.url}/fix",name:"fixFailedTasks") {
        div(class:"warning") {
            raw _("taskFailures")
        }
        ul {
            my.failedSlaves.each { failedSlave ->
                li {
                    a(href:rootURL + '/computer/' + failedSlave, failedSlave)
                }
            }
        }

        div(align:"right") {
            f.submit(name:"fix",value:_("retry failed tasks"))
        }
    }
}
