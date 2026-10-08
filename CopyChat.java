package com.beer30.treeify;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.List;

/**
 * Shift + left click a message in the open chat to copy its text to the clipboard.
 *
 * ChatHud keeps the lines it draws in a private list, so this reaches in with reflection. Every
 * lookup is wrapped: if something is not found the click is left alone (nothing is copied, the
 * game never crashes) and you get one message in chat saying why.
 */
public final class CopyChat {
    private static boolean ready;
    private static boolean warned;
    private static Field visibleField, messagesField;
    private static Method toX, toY, lineIndex;
    private static Method visContent, visEnd, lineContent;

    private CopyChat() {}

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof ChatScreen) {
                ScreenMouseEvents.allowMouseClick(screen).register(CopyChat::onClick);
            }
        });
    }

    /** Return true to let the click carry on as normal, false to swallow it. */
    private static boolean onClick(Screen screen, double mouseX, double mouseY, int button) {
        if (button != 0) return true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (!shiftHeld(client)) return true;
        try {
            String text = messageAt(client, mouseX, mouseY);
            if (text == null || text.isBlank()) return true;
            client.keyboard.setClipboard(text);
            if (client.player != null) {
                client.player.sendMessage(Text.literal("Copied chat message").formatted(Formatting.GREEN), true);
            }
            return false;
        } catch (Throwable t) {
            t.printStackTrace();
            if (!warned && client.player != null) {
                warned = true;
                client.player.sendMessage(Text.literal("[Beer30] Shift+click copy failed: " + t).formatted(Formatting.RED), false);
            }
            return true;
        }
    }

    private static boolean shiftHeld(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        return InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    /** Looks up the private pieces of ChatHud once. Names are the intermediary names of Minecraft 1.21.4. */
    private static void setup() throws Exception {
        MappingResolver r = FabricLoader.getInstance().getMappingResolver();
        String owner = "net.minecraft.class_338";   // ChatHud
        visibleField = ChatHud.class.getDeclaredField(r.mapFieldName("intermediary", owner, "field_2064", "Ljava/util/List;"));   // visibleMessages
        messagesField = ChatHud.class.getDeclaredField(r.mapFieldName("intermediary", owner, "field_2061", "Ljava/util/List;"));  // messages
        toX = ChatHud.class.getDeclaredMethod(r.mapMethodName("intermediary", owner, "method_44722", "(D)D"), double.class);    // toChatLineX
        toY = ChatHud.class.getDeclaredMethod(r.mapMethodName("intermediary", owner, "method_44724", "(D)D"), double.class);    // toChatLineY
        lineIndex = ChatHud.class.getDeclaredMethod(r.mapMethodName("intermediary", owner, "method_44725", "(DD)I"), double.class, double.class); // getMessageLineIndex
        visibleField.setAccessible(true);
        messagesField.setAccessible(true);
        toX.setAccessible(true);
        toY.setAccessible(true);
        lineIndex.setAccessible(true);

        // The line records are found by the TYPE of each component, so renamed fields cannot break this.
        for (RecordComponent rc : ChatHudLine.Visible.class.getRecordComponents()) {
            if (rc.getType() == OrderedText.class) visContent = rc.getAccessor();
            else if (rc.getType() == boolean.class) visEnd = rc.getAccessor();
        }
        for (RecordComponent rc : ChatHudLine.class.getRecordComponents()) {
            if (rc.getType() == Text.class) lineContent = rc.getAccessor();
        }
        if (visContent == null || visEnd == null) throw new IllegalStateException("could not read chat line records");
        visContent.setAccessible(true);
        visEnd.setAccessible(true);
        if (lineContent != null) lineContent.setAccessible(true);
        ready = true;
    }

    private static String messageAt(MinecraftClient client, double mouseX, double mouseY) throws Exception {
        if (!ready) setup();
        ChatHud hud = client.inGameHud.getChatHud();
        double cx = (Double) toX.invoke(hud, mouseX);
        double cy = (Double) toY.invoke(hud, mouseY);
        int idx = (Integer) lineIndex.invoke(hud, cx, cy);   // -1 when the mouse is not over a chat line
        if (idx < 0) return null;

        List<?> vis = (List<?>) visibleField.get(hud);
        if (idx >= vis.size()) return null;

        // Newest line is index 0. A message's LAST line (the one nearest the bottom) is flagged "end of
        // entry", and its other lines follow it at higher indexes until the next flagged line.
        int start = idx;
        while (start > 0 && !isEnd(vis.get(start))) start--;
        int end = start + 1;
        while (end < vis.size() && !isEnd(vis.get(end))) end++;

        StringBuilder joined = new StringBuilder();
        for (int i = end - 1; i >= start; i--) joined.append(plain(visContent.invoke(vis.get(i))));
        String fromLines = joined.toString().trim();

        // Prefer the stored full message (keeps the real spacing of wrapped lines). It is the n-th message,
        // where n = number of flagged lines before this one; only trusted if its text matches the lines.
        if (lineContent != null) {
            int n = 0;
            for (int i = 0; i < start; i++) if (isEnd(vis.get(i))) n++;
            List<?> msgs = (List<?>) messagesField.get(hud);
            if (n < msgs.size()) {
                Object content = lineContent.invoke(msgs.get(n));
                if (content instanceof Text t) {
                    String full = t.getString();
                    if (norm(full).equals(norm(fromLines))) return full.trim();
                }
            }
        }
        return fromLines;
    }

    private static boolean isEnd(Object line) throws Exception {
        return (Boolean) visEnd.invoke(line);
    }

    private static String plain(Object ordered) {
        StringBuilder sb = new StringBuilder();
        ((OrderedText) ordered).accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }

    private static String norm(String s) {
        return s.replaceAll("\\s+", "");
    }
}
