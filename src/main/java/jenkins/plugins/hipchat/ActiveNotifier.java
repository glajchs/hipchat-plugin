package jenkins.plugins.hipchat;

import hudson.Util;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.test.AbstractTestResultAction;
import org.apache.commons.lang.StringUtils;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

    private static final Logger logger = Logger.getLogger(HipChatListener.class.getName());

    HipChatNotifier notifier;

    public ActiveNotifier(HipChatNotifier notifier) {
        super();
        this.notifier = notifier;
    }

    private HipChatService getHipChat(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        String projectRoom = Util.fixEmpty(project.getProperty(HipChatNotifier.HipChatJobProperty.class).getRoom());
        return notifier.newHipChatService(projectRoom);
    }

    public void deleted(AbstractBuild r) {
    }

    public void started(AbstractBuild build) {
        String changes = getChanges(build);
        CauseAction cause = build.getAction(CauseAction.class);

        if (changes != null) {
            notifyStart(build, changes);
        } else if (cause != null) {
            MessageBuilder message = new MessageBuilder(notifier, build);
            message.append(cause.getShortDescription());
            notifyStart(build, message.toString());
        } else {
            notifyStart(build, getBuildStatusMessage(build));
        }
    }

    private void notifyStart(AbstractBuild build, String message) {
        getHipChat(build).publish(message, "green");
    }

    public void finalized(AbstractBuild r) {
    }

    public void completed(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        HipChatNotifier.HipChatJobProperty jobProperty = project.getProperty(HipChatNotifier.HipChatJobProperty.class);
        Result result = r.getResult();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild().getPreviousBuild();
        Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
        if ((result == Result.ABORTED && jobProperty.getNotifyAborted())
                || (result == Result.FAILURE && jobProperty.getNotifyFailure())
                || (result == Result.NOT_BUILT && jobProperty.getNotifyNotBuilt())
                || (result == Result.SUCCESS && previousResult == Result.FAILURE && jobProperty.getNotifyBackToNormal())
                || (result == Result.SUCCESS && jobProperty.getNotifySuccess())
                || (result == Result.UNSTABLE && jobProperty.getNotifyUnstable())) {
            getHipChat(r).publish(getBuildStatusMessage(r), getBuildColor(r));
        }
    }

    String getChanges(AbstractBuild r) {
        if (!r.hasChangeSetComputed()) {
            logger.info("No change set computed...");
            return null;
        }
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        Set<AffectedFile> files = new HashSet<AffectedFile>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
            try{
            	files.addAll(entry.getAffectedFiles());
            } catch (UnsupportedOperationException e) {
            	logger.info(e.getMessage());
            	return null;
            }
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            return null;
        }
        Set<String> authors = new HashSet<String>();
        for (Entry entry : entries) {
            authors.add(entry.getAuthor().getDisplayName());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Started by changes from ");
        message.append(StringUtils.join(authors, ", "));
        message.append(" (");
        message.append(files.size());
        message.append(" file(s) changed)");
        return message.toString();
    }

    static String getBuildColor(AbstractBuild r) {
        Result result = r.getResult();
        if (result == Result.SUCCESS) {
            return "green";
        } else if (result == Result.FAILURE || result == Result.UNSTABLE) {
            return "red";
        } else {
            return "yellow";
        }
    }

    String getBuildStatusMessage(AbstractBuild r) {
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.appendStatusMessage();
        message.appendDuration();
        message.appendTests(r);
        message.appendCulprits();
        return message.toString();
    }

    public static class MessageBuilder {
        private StringBuffer message;
        private HipChatNotifier notifier;
        private AbstractBuild build;

        public MessageBuilder(HipChatNotifier notifier, AbstractBuild build) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;
            startMessage();
        }

        public MessageBuilder appendStatusMessage() {
            message.append(getStatusMessage(build));
            return this;
        }

        static String getStatusMessage(AbstractBuild r) {
            if (r.isBuilding()) {
                return "Starting...";
            }
            Result result = r.getResult();
            Run previousBuild = r.getProject().getLastBuild().getPreviousBuild();
            Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
            if (result == Result.SUCCESS && previousResult == Result.FAILURE) return "Back to normal";
            if (result == Result.SUCCESS) return "Success";
            if (result == Result.FAILURE) return "<b>FAILURE</b>";
            if (result == Result.ABORTED) return "ABORTED";
            if (result == Result.NOT_BUILT) return "Not built";
            if (result == Result.UNSTABLE) return "Unstable";
            return "Unknown";
        }

        public MessageBuilder append(String string) {
            message.append(string);
            return this;
        }

        public MessageBuilder append(Object string) {
            message.append(string.toString());
            return this;
        }

        private MessageBuilder startMessage() {
            message.append("<img src=\"" + notifier.getBuildServerUrl() + "images/24x24/" + build.getProject().getBuildStatusUrl() + "\" alt=\"" + build.getProject().getBuildStatusUrl() + "\"/> ");
            message.append("<a href=\"" + notifier.getBuildServerUrl() + build.getUrl() + "\">" + build.getProject().getDisplayName() + "</a>");
            message.append(" - ");
            message.append(build.getDisplayName());
            message.append(" ");
            return this;
        }

        public MessageBuilder appendDuration() {
            message.append(" after ");
            message.append(build.getDurationString());
            message.append(".");
            return this;
        }

        public MessageBuilder appendTests(AbstractBuild abstractBuild) {
            AbstractTestResultAction testResults = build.getAction(AbstractTestResultAction.class);
            if (testResults == null) {
                return this;
            }
            int totalTests = testResults.getTotalCount();
            int failedTests = testResults.getFailCount();
            int skippedTests = testResults.getSkipCount();
            if (testResults.getPreviousResult() != null) {
                int previousTotalTests = testResults.getPreviousResult().getTotalCount();
                int previousFailedTests = testResults.getPreviousResult().getFailCount();
                int previousSkippedTests = testResults.getPreviousResult().getSkipCount();
                if (failedTests == 0) {
                    message.append(" " + totalTests + " tests passed");
                    if (previousTotalTests != totalTests) {
                        message.append(" (" + ((previousTotalTests > totalTests) ? "-" : "+") + Math.abs(previousTotalTests - totalTests) + " total tests)");
                    }
                    if (skippedTests > 0) {
                        message.append(" (" + skippedTests + " tests skipped)");
                        if (previousSkippedTests != skippedTests) {
                            message.append(" (" + ((previousSkippedTests > skippedTests) ? "-" : "+") + Math.abs(previousSkippedTests - skippedTests) + " skipped tests)");
                        }
                    }
                } else {
                    message.append(" " + failedTests + " of " + totalTests + " tests failed");
                    if (previousFailedTests != failedTests) {
                        message.append(" (" + ((previousFailedTests > failedTests) ? "-" : "+") + Math.abs(previousFailedTests - failedTests) + " failed tests)");
                    }
                    if (previousTotalTests != totalTests) {
                        message.append(" (" + ((previousTotalTests > totalTests) ? "-" : "+") + Math.abs(previousTotalTests - totalTests) + " total tests)");
                    }
                    if (skippedTests > 0) {
                        message.append(" (" + skippedTests + " tests skipped)");
                        if (previousSkippedTests != skippedTests) {
                            message.append(" (" + ((previousSkippedTests > skippedTests) ? "-" : "+") + Math.abs(previousSkippedTests - skippedTests) + " skipped tests)");
                        }
                    }
                }
            } else {
                if (failedTests == 0) {
                    message.append(" " + totalTests + " tests passed");
                    if (skippedTests > 0) {
                        message.append(" (" + skippedTests + " tests skipped)");
                    }
                } else {
                    message.append(" " + failedTests + " of " + totalTests + " tests failed");
                    if (skippedTests > 0) {
                        message.append(" (" + skippedTests + " tests skipped)");
                    }
                }
            }
            message.append(".");
            return this;
        }

        public MessageBuilder appendCulprits() {
            Set<User> culprits = build.getCulprits();
            if (culprits.size() > 0) {
                message.append(" Changes by ");
                Iterator culpritsIterator = culprits.iterator();
                while (culpritsIterator.hasNext()) {
                    User currentCulprit = (User) culpritsIterator.next();
                    message.append("<a href=\"" + currentCulprit.getAbsoluteUrl() + "\">" + currentCulprit.getDisplayName() + "</a>");
                    if (culpritsIterator.hasNext()) {
                        message.append(", ");
                    }
                }
                message.append(".");
            }
            return this;
        }

        public String toString() {
            return message.toString();
        }
    }
}
