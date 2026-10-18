package tw.nekomimi.nekogram;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Personal-build diagnostic: the server rejected a join-family request with
 * CHANNELS_TOO_MUCH while official clients on the same account could still
 * join, so keep the exact attribution -- method, account slot, phone, peer,
 * time -- of the most recent failure in RAM for the join-limit diagnostic
 * (LimitReachedBottomSheet) to show, and mirror it into FileLog.
 */
public class JoinFailureLog {

    private static String last = "none recorded";

    public static synchronized String lastFailure() {
        return last;
    }

    public static synchronized void record(int currentAccount, TLObject request) {
        try {
            String method = ErrorDatabase.getMethodName(request);
            TLRPC.User self = UserConfig.getInstance(currentAccount).getCurrentUser();
            String phone = self != null && self.phone != null && self.phone.length() > 4
                    ? "…" + self.phone.substring(self.phone.length() - 4) : "?";
            String peer = "";
            if (request instanceof TLRPC.TL_channels_joinChannel) {
                TLRPC.InputChannel channel = ((TLRPC.TL_channels_joinChannel) request).channel;
                if (channel != null) {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(channel.channel_id);
                    if (chat != null) {
                        peer = " -> " + chat.title + (chat.username != null ? " (@" + chat.username + ")" : "") + " [chat " + channel.channel_id + "]";
                    } else {
                        peer = " -> [chat " + channel.channel_id + ", not cached]";
                    }
                }
            }
            String when = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            last = method + " on account " + currentAccount + " (phone " + phone + ")" + peer + " at " + when;
            FileLog.e("[JoinFailureLog] CHANNELS_TOO_MUCH: " + last);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
