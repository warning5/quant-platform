package com.quant.platform.mp.service;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quant.platform.common.exception.BusinessException;
import com.quant.platform.mp.domain.SysUser;
import com.quant.platform.mp.mapper.MpUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信小程序登录：wx.login 拿到的 code -> jscode2session -> openid
 * 复用 sys_user 表（与 backend 共用），按 unionid/openid 查或自动注册，签发 Sa-Token。
 */
@Service
@Slf4j
public class MpWechatAuthService {

    private final MpUserMapper userMapper;
    private final RestTemplate restTemplate;
    private final Environment environment;

    @Value("${WECHAT_MINI_APPID:}")
    private String appId;

    @Value("${WECHAT_MINI_SECRET:}")
    private String appSecret;

    /** 开发兜底登录使用的用户 id（仅 dev profile 且未配置微信时生效） */
    @Value("${mp.dev.user-id:1}")
    private Long devUserId;

    private static final String CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public MpWechatAuthService(MpUserMapper userMapper, RestTemplate restTemplate, Environment environment) {
        this.userMapper = userMapper;
        this.restTemplate = restTemplate;
        this.environment = environment;
    }

    /**
     * 小程序登录入口
     * @return 含 token / userId / 基础用户信息的 Map
     */
    public Map<String, Object> miniLogin(String code) {
        if (!isConfigured()) {
            // dev 环境未配置微信凭据：直接以默认开发用户签发 token，便于本机联调
            if (isDev()) {
                log.warn("[MpWechat] 未配置微信凭据，dev 环境走开发兜底登录 userId={}", devUserId);
                return devLogin();
            }
            throw new BusinessException("微信登录未配置：请在 application-prod.yml 的 mp.wechat.appid/secret 中设置，或用环境变量 WECHAT_MINI_APPID / WECHAT_MINI_SECRET 注入");
        }
        JsonNode node = code2Session(code);
        if (node.has("errcode") && node.get("errcode").asInt(0) != 0) {
            throw new BusinessException("微信小程序登录失败：" + node.path("errmsg").asText());
        }
        String openid = node.path("openid").asText();
        String unionid = node.path("unionid").asText("");
        SysUser user = resolveUser(openid, unionid);

        StpUtil.login(user.getId());
        user.setLastLoginTime(LocalDateTime.now());
        userMapper.updateById(user);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", StpUtil.getTokenValue());
        result.put("tokenName", StpUtil.getTokenName());
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("nickname", user.getNickname());
        result.put("avatar", user.getAvatar());
        return result;
    }

    private SysUser resolveUser(String openid, String unionid) {
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
            user.setNickname("微信用户");
            user.setAvatar("");
            user.setWechatOpenid(openid);
            user.setWechatUnionid(unionid);
            user.setWechatType(3); // 3=小程序
            user.setStatus(1);
            user.setDeleted(0);
            userMapper.insert(user);
            log.info("[MpWechat] 新建微信用户 openid={} unionid={}", openid, unionid);
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
            if (changed) {
                userMapper.updateById(user);
            }
        }
        return user;
    }

    private JsonNode code2Session(String code) {
        String url = CODE2SESSION_URL + "?appid=" + appId
                + "&secret=" + appSecret + "&js_code=" + code + "&grant_type=authorization_code";
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

    private boolean isConfigured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    private boolean isDev() {
        return environment.acceptsProfiles(Profiles.of("dev"));
    }

    /** dev 兜底登录：不依赖微信，直接以默认开发用户签发 token */
    private Map<String, Object> devLogin() {
        StpUtil.login(devUserId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", StpUtil.getTokenValue());
        result.put("tokenName", StpUtil.getTokenName());
        result.put("userId", devUserId);
        return result;
    }
}
