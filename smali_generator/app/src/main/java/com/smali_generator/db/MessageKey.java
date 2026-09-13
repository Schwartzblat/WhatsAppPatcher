package com.smali_generator.db;

/** Identifies one WhatsApp message. Shared by the capture and render sides. */
public final class MessageKey {
    public final String id;
    public final String remoteJid;
    public final boolean fromMe;

    public MessageKey(String id, String remoteJid, boolean fromMe) {
        this.id = id;
        this.remoteJid = remoteJid;
        this.fromMe = fromMe;
    }

    @Override
    public String toString() {
        return "MessageKey{id=" + id + ", jid=" + remoteJid + ", fromMe=" + fromMe + "}";
    }
}
