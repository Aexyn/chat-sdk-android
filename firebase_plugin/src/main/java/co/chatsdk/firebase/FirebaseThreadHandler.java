package co.chatsdk.firebase;

import co.chatsdk.firebase.backendless.ChatSDKReceiver;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import org.joda.time.DateTime;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import co.chatsdk.core.NM;
import co.chatsdk.core.base.AbstractThreadHandler;
import co.chatsdk.core.dao.BMessage;
import co.chatsdk.core.dao.BThread;
import co.chatsdk.core.dao.BUser;
import co.chatsdk.core.dao.DaoCore;
import co.chatsdk.core.dao.DaoDefines;
import co.chatsdk.core.defines.FirebaseDefines;
import co.chatsdk.core.interfaces.ThreadType;
import co.chatsdk.firebase.wrappers.MessageWrapper;
import co.chatsdk.firebase.wrappers.ThreadWrapper;
import io.reactivex.Completable;
import io.reactivex.CompletableEmitter;
import io.reactivex.CompletableOnSubscribe;
import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.SingleSource;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;

/**
 * Created by benjaminsmiley-andrews on 25/05/2017.
 */

public class FirebaseThreadHandler extends AbstractThreadHandler {

    public Single<List<BMessage>> loadMoreMessagesForThread(BMessage fromMessage, BThread thread) {
        return new ThreadWrapper(thread).loadMoreMessages(fromMessage, FirebaseDefines.NumberOfMessagesPerBatch);
    }

    /** Add given users list to the given thread.
     * The RepetitiveCompletionListenerWithError will notify by his "onItem" method for each user that was successfully added.
     * In the "onItemFailed" you can get all users that the system could not add to the server.
     * When all users are added the system will call the "onDone" method.*/
    public Completable addUsersToThread(final BThread thread, final List<BUser> users) {
        return setUserThreadLinkValue(thread, users, DaoDefines.Keys.Null);

//
//        if(thread == null) {
//            return Flowable.error(new Throwable("Thread cannot be null"));
//        }
//
//        ThreadWrapper threadWrapper = new ThreadWrapper(thread);
//        ArrayList<Single<BUser>> singles = new ArrayList<>();
//
//        for (final BUser user : users){
//            singles.add(threadWrapper.addUser(UserWrapper.initWithModel(user)).toSingle(new Callable<BUser>() {
//                @Override
//                public BUser call () throws Exception {
//                    return user;
//                }
//            }));
//        }
//
//        return Single.merge(singles);
    }

    /**
     * This function is a convenience function to add or remove batches of users
     * from threads. If the value is defined, it will populate the thread/users
     * path with the user IDs. And add the thread ID to the user/threads path for
     * private threads. If value is null, the users will be removed from the thread/users
     * path and the thread will be removed from the user/threads path
     * @param thread
     * @param users
     * @param value
     * @return
     */
    public Completable setUserThreadLinkValue(final BThread thread, final List<BUser> users, final String value) {
        Completable c = Completable.create(new CompletableOnSubscribe() {
            @Override
            public void subscribe(final CompletableEmitter e) throws Exception {

                DatabaseReference ref = FirebasePaths.firebaseRef();
                final HashMap<String, Object> data = new HashMap<>();

                for (BUser u : users) {
                    PathBuilder threadUsersPath = FirebasePaths.threadUsersPath(thread.getEntityID(), u.getEntityID());
                    PathBuilder userThreadsPath = FirebasePaths.userThreadsPath(u.getEntityID(), thread.getEntityID());

                    if (value != null) {
                        threadUsersPath.a(DaoDefines.Keys.Null);
                        userThreadsPath.a(DaoDefines.Keys.Null);
                    }

                    data.put(threadUsersPath.build(), value);

                    if (thread.typeIs(ThreadType.Private)) {
                        data.put(userThreadsPath.build(), value);
                    }
                    else if (value != null) {
                        // TODO: Check this
                        // If we add users to a public thread, make sure that they are removed if we
                        // log off
                        FirebasePaths.firebaseRef().child(threadUsersPath.build()).onDisconnect().removeValue();
                    }

                }

                ref.updateChildren(data, new DatabaseReference.CompletionListener() {
                    @Override
                    public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                        if (databaseError == null) {
                            e.onComplete();
                        } else {
                            e.onError(databaseError.toException());
                        }
                    }
                });
            }
        });
        return c;
    }

    public Completable removeUsersFromThread(final BThread thread, List<BUser> users) {
        return setUserThreadLinkValue(thread, users, null);

//        if(thread == null) {
//            return Flowable.error(new Throwable("Thread cannot be null"));
//        }
//
//        ThreadWrapper threadWrapper = new ThreadWrapper(thread);
//        ArrayList<Single<BUser>> singles = new ArrayList<>();
//
//        for (final BUser user : users){
//            singles.add(threadWrapper.removeUser(UserWrapper.initWithModel(user)).toSingle(new Callable<BUser>() {
//                @Override
//                public BUser call () throws Exception {
//                    return user;
//                }
//            }));
//        }
//
//        return Single.merge(singles);
    }

    public Completable pushThread(BThread thread) {
        return new ThreadWrapper(thread).push();
    }

    /** Send a message,
     *  The message need to have a owner thread attached to it or it cant be added.
     *  If the destination thread is public the system will add the user to the message thread if needed.
     *  The uploading to the server part can bee seen her {@see FirebaseCoreAdapter#PushMessageWithComplition}.*/
    public Completable sendMessage(final BMessage message){
        return new MessageWrapper(message).send().doOnComplete(new Action() {
            @Override
            public void run() throws Exception {
                // Setting the time stamp for the last message added to the thread.
                DatabaseReference threadRef = FirebasePaths.threadRef(message.getThread().getEntityID()).child(FirebasePaths.DetailsPath);

                threadRef.updateChildren(FirebasePaths.getMap(new String[]{DaoDefines.Keys.LastMessageAdded}, ServerValue.TIMESTAMP));

                // Pushing the message to all offline users. we cant push it before the message was
                // uploaded as the date is saved by the firebase server using the timestamp.
                pushForMessage(message);
            }
        });
    }

    /**
     * Create thread for given users.
     *  When the thread is added to the server the "onMainFinished" will be invoked,
     *  If an error occurred the error object would not be null.
     *  For each user that was successfully added the "onItem" method will be called,
     *  For any item adding failure the "onItemFailed will be called.
     *   If the main task will fail the error object in the "onMainFinished" method will be called."
     **/
    public Single<BThread> createThread(final List<BUser> users) {
        return createThread(null, users);
    }

    public Single<BThread> createThread(final String name, final List<BUser> users) {
        return Single.create(new SingleOnSubscribe<BThread>() {
            @Override
            public void subscribe(final SingleEmitter<BThread> e) throws Exception {

                BUser currentUser = NM.currentUser();

                if(!users.contains(currentUser)) {
                    users.add(currentUser);
                }

                if(users.size() == 2) {

                    BUser otherUser = null;
                    BThread jointThread = null;

                    for(BUser user : users) {
                        if(!user.equals(currentUser)) {
                            otherUser = user;
                            break;
                        }
                    }

                    // Check to see if a thread already exists with these
                    // two users

                    for(BThread thread : getThreads(ThreadType.Private1to1)) {
                        if(thread.getUsers().size() == 2 &&
                                thread.getUsers().contains(currentUser) &&
                                thread.getUsers().contains(otherUser))
                        {
                            jointThread = thread;
                            break;
                        }
                    }

                    if(jointThread != null) {
                        jointThread.setDeleted(false);
                        DaoCore.updateEntity(jointThread);
                        e.onSuccess(jointThread);
                        return;
                    }
                }

                final BThread thread = DaoCore.getEntityForClass(BThread.class);
                DaoCore.createEntity(thread);
                thread.setCreator(currentUser);
                thread.setCreatorEntityId(currentUser.getEntityID());
                thread.setCreationDate(new Date());
                thread.setName(name);
                thread.setType(users.size() == 2 ? ThreadType.Private1to1 : ThreadType.PrivateGroup);

                // Save the thread to the database.
                e.onSuccess(thread);

            }
        }).flatMap(new Function<BThread, SingleSource<? extends BThread>>() {
            @Override
            public SingleSource<? extends BThread> apply(final BThread thread) throws Exception {
                return Single.create(new SingleOnSubscribe<BThread>() {
                    @Override
                    public void subscribe(final SingleEmitter<BThread> e) throws Exception {
                        if(thread.getEntityID() == null) {
                            ThreadWrapper wrapper = new ThreadWrapper(thread);
                            wrapper.push().concatWith(addUsersToThread(thread, users)).doOnComplete(new Action() {
                                @Override
                                public void run() throws Exception {
                                    e.onSuccess(thread);
                                }
                            }).subscribe();
                        }
                        else {
                            e.onSuccess(thread);
                        }
                    }
                });
            }
        }).doOnSuccess(new Consumer<BThread>() {
            @Override
            public void accept(BThread thread) throws Exception {
                DaoCore.connectUserAndThread(NM.currentUser(),thread);
                DaoCore.updateEntity(thread);
            }
        });
    }

    public Completable deleteThread(BThread thread) {
        return deleteThreadWithEntityID(thread.getEntityID());
    }

    public Completable deleteThreadWithEntityID(final String entityID) {
        final BThread thread = DaoCore.fetchEntityWithEntityID(BThread.class, entityID);
        return new ThreadWrapper(thread).deleteThread();
    }

    protected void pushForMessage(final BMessage message){
        if (NM.push() == null)
            return;

        if (message.getThread().typeIs(ThreadType.Private)) {

            // Loading the message from firebase to get the timestamp from server.
            DatabaseReference firebase = FirebasePaths.threadRef(message.getThread().getEntityID())
                    .child(FirebasePaths.MessagesPath)
                    .child(message.getEntityID());

            firebase.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    Long date = null;
                    try {
                        date = (Long) snapshot.child(DaoDefines.Keys.Date).getValue();
                    } catch (ClassCastException e) {
                        date = (((Double)snapshot.child(DaoDefines.Keys.Date).getValue()).longValue());
                    }
                    finally {
                        if (date != null)
                        {
                            message.setDate(new DateTime(date));
                            DaoCore.updateEntity(message);
                        }
                    }

                    // If we failed to get date dont push.
                    if (message.getDate()==null)
                        return;

                    BUser currentUser = NM.currentUser();
                    List<BUser> users = new ArrayList<BUser>();

                    for (BUser user : message.getThread().getUsers())
                        if (!user.equals(currentUser))
                            if (!user.equals(currentUser)) {
                                // Timber.v(user.getEntityID() + ", " + user.getOnline().toString());
                                // sends push notification regardless of receiver online status
                                // TODO: add observer to online status
                                // if (user.getOnline() == null || !user.getOnline())
                                users.add(user);
                            }

                    pushToUsers(message, users);
                }

                @Override
                public void onCancelled(DatabaseError firebaseError) {

                }
            });
        }
    }

    protected void pushToUsers(BMessage message, List<BUser> users){

        if (NM.push() == null || users.size() == 0)
            return;

        // We're identifying each user using push channels. This means that
        // when a user signs up, they register with backendless on a particular
        // channel. In this case user_[user id] this means that we can
        // send a push to a specific user if we know their user id.
        List<String> channels = new ArrayList<String>();
        for (BUser user : users)
            channels.add(user.getPushChannel());

        String messageText = message.getTextString();

        if (message.getType() == BMessage.Type.LOCATION)
            messageText = "Location CoreMessage";
        else if (message.getType() == BMessage.Type.IMAGE)
            messageText = "Picture CoreMessage";

        String sender = message.getSender().getMetaName();
        String fullText = sender + " " + messageText;

        JSONObject data = new JSONObject();
        try {
            data.put(DaoDefines.Keys.ACTION, ChatSDKReceiver.ACTION_MESSAGE);

            data.put(DaoDefines.Keys.CONTENT, fullText);
            data.put(DaoDefines.Keys.MESSAGE_ENTITY_ID, message.getEntityID());
            data.put(DaoDefines.Keys.THREAD_ENTITY_ID, message.getThread().getEntityID());
            data.put(DaoDefines.Keys.MESSAGE_DATE, message.getDate().toDate().getTime());
            data.put(DaoDefines.Keys.MESSAGE_SENDER_ENTITY_ID, message.getSender().getEntityID());
            data.put(DaoDefines.Keys.MESSAGE_SENDER_NAME, message.getSender().getMetaName());
            data.put(DaoDefines.Keys.MESSAGE_TYPE, message.getType());
            data.put(DaoDefines.Keys.MESSAGE_PAYLOAD, message.getTextString());
            //For iOS
            data.put(DaoDefines.Keys.BADGE, DaoDefines.Keys.INCREMENT);
            data.put(DaoDefines.Keys.ALERT, fullText);
            // For making sound in iOS
            data.put(DaoDefines.Keys.SOUND, DaoDefines.Keys.Default);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        NM.push().pushToChannels(channels, data);
    }

    public Completable leaveThread (BThread thread) {
        return null;
    }

    public Completable joinThread (BThread thread) {
        return null;
    }

}