package com.itemban.client;

import com.itemban.AdminKeyManager;
import com.itemban.AdminNetwork;
import com.itemban.ItemBan;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TextComponent;

import java.util.List;

public final class ClientAdminHandler {
    private ClientAdminHandler() {}

    public static void onChallenge(String keyId, byte[] nonce) {
        List<ClientAdminTokens.Token> tokens = ClientAdminTokens.loadAll();
        ClientAdminTokens.Token matched = ClientAdminTokens.match(keyId, tokens);
        if (matched == null) {
            fail("未找到与本服密钥匹配的管理模组（可同时安装多个服的管理模组）");
            return;
        }
        String hmac = AdminKeyManager.hmacHex(matched.key, nonce);
        AdminNetwork.sendProof(matched.keyId, hmac, matched.tokenHash, matched.jarSha256);
    }

    public static void openScreen(String json) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new AdminScreen(json)));
    }

    public static void fail(String reason) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(new TextComponent("§c" + reason), false);
            }
            ItemBan.LOGGER.warn("打开 ItemBan 管理界面失败: {}", reason);
        });
    }
}
