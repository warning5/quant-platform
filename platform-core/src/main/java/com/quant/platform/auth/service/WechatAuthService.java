package com.quant.platform.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.quant.platform.auth.config.WechatProperties;
import com.quant.platform.auth.dto.LoginVO;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.system.entity.SysUser;
import com.quant.platform.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 微信联合登录：网站应用扫码 / 公众号网页授权 / 小程序 code2session
 * 注：微信接口调用依赖真实 AppId/Secret（来自 .env），未配置时相关登录会直接报错提示。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WechatAuthService {

    private final WechatProperties wechat;
    private final RestTemplate restTemplate;
    private final SysUserMapper userMapper;
    private final AuthService authService;

    private static final String AUTHORIZE_BASE = "https://open.weixin.qq.com/connect";
    private static final String API_BASE = "https://api.weixin.qq.com/sns";
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // ---------------- 授权 URL ----------------

    public String buildWebsiteAuthorizeUrl() {
        String redirect = UriUtils.encode(wechat.getWeb().getRedirectUri(), StandardCharsets.UTF_8);
        return AUTHORIZE_BASE + "/qrconnect?appid=" + wechat.getWeb().getAppId()
                + "&redirect_uri=" + redirect
                + "&response_type=code&scope=snsapi_login&state=" + state() + "#wechat_redirect";
    }

    public String buildMpAuthorizeUrl() {
        String redirect = UriUtils.encode(wechat.getMp().getRedirectUri(), StandardCharsets.UTF_8);
        return AUTHORIZE_BASE + "/oauth2/authorize?appid=" + wechat.getMp().getAppId()
                + "&redirect_uri=" + redirect
                + "&response_type=code&scope=snsapi_userinfo&state=" + state() + "#wechat_redirect";
    }

    // ---------------- 回调 / 登录 ----------------

    /** 网站应用回调：code -> access_token -> userinfo */
    public LoginVO handleWebsiteCallback(String code) {
        WechatProperties.Web cfg = wechat.getWeb();
        checkConfigured(cfg.getAppId(), cfg.getAppSecret());
        JsonNode tokenNode = getAccessToken(cfg.getAppId(), cfg.getAppSecret(), code);
        String openid = tokenNode.path("openid").asText();
        String unionid = tokenNode.path("unionid").asText("");
        JsonNode info = getUserInfo(tokenNode.path("access_token").asText(), openid);
        String nickname = info.path("nickname").asText("");
        String avatar = info.path("headimgurl").asText("");
        return resolveUser(openid, unionid, nickname, avatar, 1);
    }

    /** 公众号网页授权回调 */
    public LoginVO handleMpCallback(String code) {
        WechatProperties.Mp cfg = wechat.getMp();
        checkConfigured(cfg.getAppId(), cfg.getAppSecret());
        JsonNode tokenNode = getAccessToken(cfg.getAppId(), cfg.getAppSecret(), code);
        String openid = tokenNode.path("openid").asText();
        String unionid = tokenNode.path("unionid").asText("");
        JsonNode info = getUserInfo(tokenNode.path("access_token").asText(), openid);
        String nickname = info.path("nickname").asText("");
        String avatar = info.path("headimgurl").asText("");
        return resolveUser(openid, unionid, nickname, avatar, 2);
    }

    /** 小程序登录：wx.login 拿到的 code -> openid */
    public LoginVO miniLogin(String code) {
        WechatProperties.Mini cfg = wechat.getMini();
        checkConfigured(cfg.getAppId(), cfg.getAppSecret());
        JsonNode node = code2Session(cfg.getAppId(), cfg.getAppSecret(), code);
        if (node.has("errcode") && node.get("errcode").asInt(0) != 0) {
            throw new BusinessException("微信小程序登录失败：" + node.path("errmsg").asText());
        }
        String openid = node.path("openid").asText();
        String unionid = node.path("unionid").asText("");
        return resolveUser(openid, unionid, "", "", 3);
    }

    // ---------------- 内部工具 ----------------

    private LoginVO resolveUser(String openid, String unionid, String nickname, String avatar, int type) {
        SysUser user = null;
        if (unionid != null && !unionid.isBlank()) {
            user = userMapper.selectByUnionid(unionid);
        }
        if (user == null && openid != null && !openid.isBlank()) {
            user = userMapper.selectByOpenid(openid);
        }
        if (user == null) {
            user = new SysUser();
            user.setUsername("wx_" + (unionid != null && !unionid.isBlank() ? unionid : openid));
            user.setNickname(nickname != null && !nickname.isBlank() ? nickname : "微信用户");
            user.setAvatar(avatar != null ? avatar : "");
            user.setWechatOpenid(openid);
            user.setWechatUnionid(unionid);
            user.setWechatType(type);
            user.setStatus(1);
            userMapper.insert(user);
            log.info("[Wechat] 新建微信用户 openid={} unionid={}", openid, unionid);
        } else {
            boolean changed = false;
            if ((user.getWechatUnionid() == null || user.getWechatUnionid().isBlank())
                    && unionid != null && !unionid.isBlank()) {
                user.setWechatUnionid(unionid);
                changed = true;
            }
            if ((user.getWechatOpenid() == null || user.getWechatOpenid().isBlank())
                    && openid != null && !openid.isBlank()) {
                user.setWechatOpenid(openid);
                changed = true;
            }
            if (avatar != null && !avatar.isBlank()
                    && (user.getAvatar() == null || user.getAvatar().isBlank())) {
                user.setAvatar(avatar);
                changed = true;
            }
            if (changed) {
                userMapper.updateById(user);
            }
        }
        return authService.issueToken(user);
    }

    private JsonNode getAccessToken(String appId, String secret, String code) {
        String url = API_BASE + "/oauth2/access_token?appid=" + appId
                + "&secret=" + secret + "&code=" + code + "&grant_type=authorization_code";
        return parse(restTemplate.getForObject(url, String.class), "获取 access_token");
    }

    private JsonNode getUserInfo(String accessToken, String openid) {
        String url = API_BASE + "/userinfo?access_token=" + accessToken
                + "&openid=" + openid + "&lang=zh_CN";
        return parse(restTemplate.getForObject(url, String.class), "获取用户信息");
    }

    private JsonNode code2Session(String appId, String secret, String code) {
        String url = API_BASE + "/jscode2session?appid=" + appId
                + "&secret=" + secret + "&js_code=" + code + "&grant_type=authorization_code";
        return parse(restTemplate.getForObject(url, String.class), "code2session");
    }

    private JsonNode parse(String body, String step) {
        if (body == null) {
            throw new BusinessException("微信" + step + "返回为空");
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(body);
            if (node.has("errcode") && node.get("errcode").asInt(0) != 0) {
                throw new BusinessException("微信" + step + "失败：" + node.path("errmsg").asText());
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("微信" + step + "返回解析失败：" + body);
        }
    }

    private void checkConfigured(String appId, String secret) {
        if (appId == null || appId.isBlank() || secret == null || secret.isBlank()) {
            throw new BusinessException("微信登录未配置：请在 .env 中设置对应的 WECHAT_*_APPID / WECHAT_*_SECRET");
        }
    }

    private String state() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 前端基础地址（供回调重定向使用） */
    public String getFrontendBaseUrl() {
        return wechat.getFrontendBaseUrl();
    }
}
