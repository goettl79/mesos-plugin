package org.jenkinsci.plugins.mesos.monitoring.MesosTaskFailureMonitor

def f = namespace(lib.FormTagLib)

if (my.isFixingActive()) {
    div(class:"info") {
        raw _("inProgress",my.url)
    }
} else if (my.logFile.exists()) {
    div(class:"info") {
        raw _("completed",my.url)
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
                    a(href:rootURL + '/computer/' + failedSlave.key, "${failedSlave.key} (${failedSlave.value})")
                }
            }
        }

        div(align:"right") {
            f.submit(name:"fix",value:_("Retry failed tasks"))
            f.submit(name:"dismiss",value:_("Ignore failed tasks (no reprovisioning)"))
        }
    }
}
