package net.robinfriedli.botify.command.widget;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.GroovyShell;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.robinfriedli.botify.Botify;
import net.robinfriedli.botify.command.CommandContext;
import net.robinfriedli.botify.command.CommandManager;
import net.robinfriedli.botify.concurrent.CompletableFutures;
import net.robinfriedli.botify.discord.DiscordEntity;
import net.robinfriedli.botify.discord.MessageService;
import net.robinfriedli.botify.entities.xml.WidgetContribution;
import net.robinfriedli.botify.exceptions.UserException;

/**
 * A widget is an interactive message that can be interacted with by adding reactions. Different reactions trigger
 * different actions that are represented by the reaction's emote.
 */
public abstract class AbstractWidget {

    private final CommandManager commandManager;
    private final WidgetContribution widgetContribution;
    private final WidgetManager widgetManager;
    private final WidgetRegistry widgetRegistry;
    private final DiscordEntity.Guild guild;
    private final DiscordEntity.MessageChannel channel;
    private final Logger logger;

    private DiscordEntity.Message message;
    private volatile CompletableFuture<Boolean> pendingMessageDeletion;
    private volatile boolean messageDeleted;

    protected AbstractWidget(WidgetRegistry widgetRegistry, Guild guild, MessageChannel channel) {
        Botify botify = Botify.get();
        this.commandManager = botify.getCommandManager();
        this.widgetManager = botify.getWidgetManager();
        this.widgetRegistry = widgetRegistry;
        this.guild = new DiscordEntity.Guild(guild);
        this.channel = DiscordEntity.MessageChannel.createForMessageChannel(channel);
        widgetContribution = widgetManager.getContributionForWidget(getClass());
        logger = LoggerFactory.getLogger(getClass());
    }

    /**
     * Resets (i.e. re-sends) the message output after the widget action has been executed. The new message needs to be
     * attached to a new instance of this widget.
     */
    public abstract void reset();

    public abstract CompletableFuture<Message> prepareInitialMessage();

    public DiscordEntity.Message getMessage() {
        return message;
    }

    public DiscordEntity.Guild getGuild() {
        return guild;
    }

    public DiscordEntity.MessageChannel getChannel() {
        return channel;
    }

    public WidgetContribution getWidgetContribution() {
        return widgetContribution;
    }

    /**
     * Set up the widget so that it is ready to be used. This creates and sends the initial message if necessary,
     * registers the widget in the guild's {@link WidgetRegistry} so that it can be found when receiving reactions and
     * sets up all actions by adding the corresponding reaction emote to the message.
     */
    public void initialise() {
        CompletableFuture<Message> futureMessage = prepareInitialMessage();
        try {
            Message message = futureMessage.get();
            this.message = new DiscordEntity.Message(message);
            widgetRegistry.registerWidget(this);
            setupActions(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new RuntimeException("Could not initialise widget since setting up the message failed", e);
        }
    }

    /**
     * Adds the reactions representing each available action to the message and prepares all actions
     */
    public void setupActions(Message message) {
        List<WidgetManager.WidgetActionDefinition> actions = widgetManager.getActionsForWidget(getClass());

        GroovyShell predicateEvaluationShell = new GroovyShell();
        predicateEvaluationShell.setVariable("widget", this);

        try {
            CompletableFuture<RuntimeException> futureException = new CompletableFuture<>();

            for (int i = 0; i < actions.size(); i++) {
                WidgetContribution.WidgetActionContribution action = actions.get(i).getImplementation();

                if (action.hasAttribute("displayPredicate")) {
                    try {
                        if (!((boolean) predicateEvaluationShell.evaluate(action.getAttribute("displayPredicate").getValue()))) {
                            break;
                        }
                    } catch (ClassCastException e) {
                        throw new IllegalStateException(String.format("Groovy script in displayPredicate of action contribution %s did not return a boolean", action), e);
                    } catch (Exception e) {
                        throw new IllegalStateException("Exception occurred evaluating displayPredicate of action contribution " + action, e);
                    }
                }

                int finalI = i;
                message.addReaction(action.getEmojiUnicode()).queue(aVoid -> {
                    if (finalI == actions.size() - 1 && !futureException.isDone()) {
                        futureException.cancel(false);
                    }
                }, throwable -> {
                    if (throwable instanceof InsufficientPermissionException || throwable instanceof ErrorResponseException) {
                        if (!futureException.isDone()) {
                            futureException.complete((RuntimeException) throwable);
                        }
                    } else {
                        logger.warn("Unexpected exception while adding reaction", throwable);
                    }
                });
            }

            futureException.thenAccept(e -> {
                MessageService messageService = Botify.get().getMessageService();
                if (e instanceof InsufficientPermissionException) {
                    messageService.sendError("Bot is missing permission: "
                        + ((InsufficientPermissionException) e).getPermission().getName(), message.getChannel());
                } else if (e instanceof ErrorResponseException) {
                    int errorCode = ((ErrorResponseException) e).getErrorCode();
                    if (errorCode == 50013) {
                        messageService.sendError("Bot is missing permission to add reactions", message.getChannel());
                    } else if (errorCode != 10008) {
                        // ignore errors thrown when the message has been deleted
                        logger.warn("Could not add reaction to message " + message, e);
                    }
                }
            });
        } catch (InsufficientPermissionException e) {
            // exception is actually never thrown when it should be, remove completable future hack if this ever changes
            throw new UserException("Bot is missing permission: " + e.getPermission().getName(), e);
        } catch (ErrorResponseException e) {
            if (e.getErrorCode() == 50013) {
                throw new UserException("Bot is missing permission to add reactions");
            } else {
                logger.warn("Could not add reaction to message " + message, e);
            }
        }
    }

    public void destroy() {
        widgetRegistry.removeWidget(this);
        awaitMessageDeletionIfPendingThenDo(deleted -> {
            if (!deleted) {
                Message retrievedMessage = message.retrieve();

                if (retrievedMessage == null) {
                    return;
                }

                try {
                    try {
                        retrievedMessage.delete().queue();
                    } catch (InsufficientPermissionException ignored) {
                        retrievedMessage.clearReactions().queue();
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void handleReaction(GuildMessageReactionAddEvent event, CommandContext context) {
        widgetManager
            .getWidgetActionDefinitionForReaction(getClass(), event.getReaction())
            .map(actionDefinition -> actionDefinition.getImplementation().instantiate(context, this, event, actionDefinition))
            .ifPresent(commandManager::runCommand);
    }

    public WidgetRegistry getWidgetRegistry() {
        return widgetRegistry;
    }

    /**
     * This method is used to run any logic that checks if this widget has deleted its associated message. If the widget
     * is currently in the process of deleting the message, i.e. if pendingMessageDeletion is set to a non completed future
     * but not null, this will execute the given code after the pendingMessageDeletion future is complete. Else this
     * simply checks the value of the messageDeleted field and apply it to the given consumer.
     *
     * @param resultConsumer The logic to run that depends on whether or not the message of this widget has been deleted.
     */
    public void awaitMessageDeletionIfPendingThenDo(Consumer<Boolean> resultConsumer) {
        if (pendingMessageDeletion != null) {
            CompletableFutures.thenAccept(pendingMessageDeletion, resultConsumer);
        } else {
            resultConsumer.accept(messageDeleted);
        }
    }

    public void setMessageDeleted(boolean messageDeleted) {
        // calls to #awaitMessageDeletionIfPendingThenDo while the deletion is in progress will await the completable future
        // and see the completion of the completable future instance; calls made to that method after deletion will see
        // the altered messageDeleted field; the pending deletion future is set back to null for in case this widget sends
        // a new message, i.e. after resetting
        this.messageDeleted = messageDeleted;

        if (pendingMessageDeletion != null) {
            pendingMessageDeletion.complete(messageDeleted);
            pendingMessageDeletion = null;
        }
    }

    public void awaitMessageDeletion() {
        pendingMessageDeletion = new CompletableFuture<>();
    }

}