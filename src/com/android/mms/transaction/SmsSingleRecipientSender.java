package com.android.mms.transaction;

import java.util.ArrayList;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony.Mms;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.util.Log;

import com.android.mms.LogTag;
import com.android.mms.MmsConfig;
import com.android.mms.MmsPluginManager;
import com.google.android.mms.MmsException;
import android.provider.Telephony.Sms;

import com.android.mms.data.Conversation;
import com.android.mms.ui.MessageUtils;

/// M:
import android.os.Bundle;
import android.os.SystemProperties;
import android.telephony.SmsMessage;

import com.android.mms.MmsApp;
import com.android.internal.telephony.PhoneConstants;
import com.android.mms.util.PhoneUtils;
import com.android.mms.util.MmsLog;
import com.mediatek.mms.ext.IMmsSmsMessageSenderExt;
import com.mediatek.mms.ext.IMmsUtilsExt;
import com.mediatek.mms.ext.DefaultMmsSmsMessageSenderExt;
import com.mediatek.mms.ext.IMmsPreferenceExt;
import com.mediatek.common.MPlugin;

public class SmsSingleRecipientSender extends SmsMessageSender {

    private final boolean mRequestDeliveryReport;
    private String mDest;
    private Uri mUri;
    private static final String TAG = "SmsSingleRecipientSender";

    /// M: OP09 plug-in @{
    private IMmsSmsMessageSenderExt mMmsSmsMessageSenderPlugin = null;
    private IMmsUtilsExt mMmsUtilsPlugin = null;
    /// M: @}

    public SmsSingleRecipientSender(Context context, String dest, String msgText, long threadId,
            boolean requestDeliveryReport, Uri uri, int subId) {
        super(context, null, msgText, threadId, subId);
        mRequestDeliveryReport = requestDeliveryReport;
        mDest = dest;
        mUri = uri;

        /// M: init plug-in.
        initPlugin(context);
    }

    public boolean sendMessage(long token) throws MmsException {
        if (LogTag.DEBUG_SEND) {
            Log.v(TAG, "sendMessage token: " + token);
        }
        if (mMessageText == null) {
            // Don't try to send an empty message, and destination should be just
            // one.
            throw new MmsException("Null message body or have multiple destinations.");
        }

        /// M:Code analyze 002,add a variable to caculate the length of sms @{
        int codingType = SmsMessage.ENCODING_UNKNOWN;
        if (MmsConfig.isEnableSmsEncodingType()) {
            codingType = MessageUtils.getSmsEncodingType(mContext);
        }
        /// @}

        // if (!PhoneUtils.isValidSubId(mContext, mSubId)) {
        // // Make last check of the validity of current SIM. It is possible
        // that
        // // it is removed.
        // throw new MmsException("Current selected SIM is not valid");
        // }
        SmsManager smsManager = SmsManager.getSmsManagerForSubscriptionId(mSubId);
        ArrayList<String> messages = null;
        if ((MmsConfig.getEmailGateway() != null) &&
                (Mms.isEmailAddress(mDest) || MessageUtils.isAlias(mDest))) {
            String msgText;
            msgText = mDest + " " + mMessageText;
            mDest = MmsConfig.getEmailGateway();
            /// M:Code analyze 003,add a parameter codingType to caculate length of sms @{
            messages = smsManager.divideMessage(msgText, codingType);
            /// @}
        } else {
            /// M:Code analyze 003,add a parameter codingType to caculate length of sms @{
            messages = smsManager.divideMessage(mMessageText, codingType);
            /// @}
            // remove spaces and dashes from destination number
            // (e.g. "801 555 1212" -> "8015551212")
            // (e.g. "+8211-123-4567" -> "+82111234567")
            /// M:Code analyze 004, comment the line,using customized striping pattern to mDest @{
            //mDest = PhoneNumberUtils.stripSeparators(mDest);
            /** M: remove spaces from destination number (e.g. "801 555 1212" -> "8015551212") @{ */
            mDest = mDest.replaceAll(" ", "");
            mDest = mDest.replaceAll("-", "");
            /// @}
            mDest = Conversation.verifySingleRecipient(mContext, mThreadId, mDest);
        }
        int messageCount = messages.size();
        /// M:
        MmsLog.d(MmsApp.TXN_TAG, "SmsSingleRecipientSender: sendMessage(), Message Count=" + messageCount);

        if (messageCount == 0) {
            // Don't try to send an empty message.
            throw new MmsException("SmsMessageSender.sendMessage: divideMessage returned " +
                    "empty messages. Original message is \"" + mMessageText + "\"");
        }

        boolean moved = Sms.moveMessageToFolder(mContext, mUri, Sms.MESSAGE_TYPE_OUTBOX, 0);
        if (!moved) {
            throw new MmsException("SmsMessageSender.sendMessage: couldn't move message " +
                    "to outbox: " + mUri);
        }
        if (LogTag.DEBUG_SEND) {
            Log.v(TAG, "sendMessage mDest: " + mDest + " mRequestDeliveryReport: " +
                    mRequestDeliveryReport);
        }

        ArrayList<PendingIntent> deliveryIntents =  new ArrayList<PendingIntent>(messageCount);
        ArrayList<PendingIntent> sentIntents = new ArrayList<PendingIntent>(messageCount);
        for (int i = 0; i < messageCount; i++) {
            if (mRequestDeliveryReport && (i == (messageCount - 1))) {
                /// M: For OP09 feature, do not request delivery report when in international roaming status.
                // [L-migration] Songmin-OP09
                if (!MmsConfig.isAllowDRWhenRoaming(mContext, (int) mSubId)) {
                    MmsLog.d(MmsApp.TXN_TAG, "Disable DR request when roaming!");
                    deliveryIntents.add(null);
                } else {
                    // TODO: Fix: It should not be necessary to
                    // specify the class in this intent.  Doing that
                    // unnecessarily limits customizability.
                    deliveryIntents.add(PendingIntent.getBroadcast(
                            mContext, 0,
                            new Intent(
                                    MessageStatusReceiver.MESSAGE_STATUS_RECEIVED_ACTION,
                                    mUri,
                                    mContext,
                                    MessageStatusReceiver.class)
                                            .putExtra(PhoneConstants.SUBSCRIPTION_KEY, mSubId),
                                    0));
                }
            } else {
                deliveryIntents.add(null);
            }
            Intent intent  = new Intent(SmsReceiverService.MESSAGE_SENT_ACTION,
                    mUri,
                    mContext,
                    SmsReceiver.class);
            /// M:Code analyze 007, comment the line,using different requestCode for every sub_message @{
            //int requestCode = 0;
            /// @}
            if (i == messageCount - 1) {
                // Changing the requestCode so that a different pending intent
                // is created for the last fragment with
                // EXTRA_MESSAGE_SENT_SEND_NEXT set to true.
                /// M:Code analyze 007, comment the line,using different requestCode for every sub_message @{
                //requestCode = 1;
                /// @}
                intent.putExtra(SmsReceiverService.EXTRA_MESSAGE_SENT_SEND_NEXT, true);
            }

            /// M:Code analyze 008, add for concatenation msg @{
            if (messageCount > 1) {
                intent.putExtra(SmsReceiverService.EXTRA_MESSAGE_CONCATENATION, true);
            }
            /// @}
            if (LogTag.DEBUG_SEND) {
                Log.v(TAG, "sendMessage sendIntent: " + intent);
            }
            intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, mSubId);
            /// M:Code analyze 007, comment the line,using different requestCode for every sub_message @{
            sentIntents.add(PendingIntent.getBroadcast(mContext, i, intent, 0));
            /// @}
        }
        try {
            /// M:Code analyze 008, print log @{
            MmsLog.d(MmsApp.TXN_TAG, "\t Destination\t= " + mDest);
            MmsLog.d(MmsApp.TXN_TAG, "\t ServiceCenter\t= " + mServiceCenter);
            MmsLog.d(MmsApp.TXN_TAG, "\t Message\t= " + messages);
            MmsLog.d(MmsApp.TXN_TAG, "\t uri\t= " + mUri);
            MmsLog.d(MmsApp.TXN_TAG, "\t subId\t= " + mSubId);
            MmsLog.d(MmsApp.TXN_TAG, "\t CodingType\t= " + codingType);
            /// @}

            IMmsPreferenceExt prefPlugin = (IMmsPreferenceExt) MmsPluginManager
                    .getMmsPluginObject(MmsPluginManager.MMS_PLUGIN_TYPE_PREFERENCE);
            // [L-migration] Songmin-plugin prama
            Bundle extra = prefPlugin.getSmsValidityParamBundleWhenSend(mContext, mSubId);
            if (extra != null) {
                smsManager.sendMultipartTextMessageWithExtraParams(mDest, mServiceCenter,
                        messages, extra, sentIntents, deliveryIntents);
                /// M: OP09 Feature, Send SMS with priority. @{
            } else if (MmsConfig.isSmsPriorityEnable()
                && mMmsUtilsPlugin.isCDMAType(mContext, mSubId)
                && !MmsConfig.isAllowDRWhenRoaming(mContext, (int) mSubId)) {
                mMmsSmsMessageSenderPlugin.sendSMSWithPriority(mContext, mDest, mServiceCenter,
                    messages, mSubId, sentIntents, deliveryIntents);
                /// @}
            } else {
                smsManager.sendMultipartTextMessageWithEncodingType(mDest,
                        mServiceCenter, messages, codingType, sentIntents,
                        deliveryIntents);
            }
            /// @}
            /// M:add this for Sms emulator update sms status @{
            String enableNowSMS = SystemProperties.get("net.ENABLE_NOWSMS");
            if (enableNowSMS.equals("true")) {
                Sms.moveMessageToFolder(mContext, mUri, Sms.MESSAGE_TYPE_SENT, 0);
            }
            /// @}
        } catch (Exception ex) {
            Log.e(TAG, "SmsMessageSender.sendMessage: caught", ex);
            throw new MmsException("SmsMessageSender.sendMessage: caught " + ex +
                    " from SmsManager.sendTextMessage()");
        }
        if (Log.isLoggable(LogTag.TRANSACTION, Log.VERBOSE) || LogTag.DEBUG_SEND) {
            log("sendMessage: address=" + mDest + ", threadId=" + mThreadId +
                    ", uri=" + mUri + ", msgs.count=" + messageCount);
        }
        return false;
    }

    private void log(String msg) {
        Log.d(LogTag.TAG, "[SmsSingleRecipientSender] " + msg);
    }

    /// M: For OP09 Plug-in.
    private void initPlugin(Context context) {
         mMmsSmsMessageSenderPlugin = (IMmsSmsMessageSenderExt) MPlugin.createInstance(
                IMmsSmsMessageSenderExt.class.getName(), context);
         if (mMmsSmsMessageSenderPlugin == null) {
            mMmsSmsMessageSenderPlugin = new DefaultMmsSmsMessageSenderExt(context);
            MmsLog.d(TAG, "default mMmsSmsMessageSenderPlugin = " + mMmsSmsMessageSenderPlugin);
        }

        mMmsUtilsPlugin = (IMmsUtilsExt) MmsPluginManager.getMmsPluginObject(MmsPluginManager.MMS_PLUGIN_TYPE_MESSAGE_UTILS);
    }
}
