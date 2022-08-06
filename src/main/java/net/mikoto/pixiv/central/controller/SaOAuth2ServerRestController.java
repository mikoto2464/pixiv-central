package net.mikoto.pixiv.central.controller;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.oauth2.config.SaOAuth2Config;
import cn.dev33.satoken.oauth2.logic.SaOAuth2Handle;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.util.SaResult;
import com.alibaba.fastjson2.JSONObject;
import net.mikoto.pixiv.central.dao.UserRepository;
import net.mikoto.pixiv.central.service.CaptchaService;
import net.mikoto.pixiv.core.model.User;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Date;

import static net.mikoto.pixiv.central.util.RandomString.getRandomString;
import static net.mikoto.pixiv.core.util.RsaUtil.decrypt;
import static net.mikoto.pixiv.core.util.RsaUtil.getPrivateKey;
import static net.mikoto.pixiv.core.util.Sha256Util.getSha256;

/**
 * @author mikoto
 * Create for pixiv-central
 * Create at 2022/8/1
 */
@SuppressWarnings("AlibabaClassNamingShouldBeCamel")
@RestController
public class SaOAuth2ServerRestController {
    public static final String RE_CAPTCHA_RESPONSE = "reCaptchaResponse";
    public static final String USER_NAME = "userName";
    public static final String USER_PASSWORD = "userPassword";

    @Value("${mikoto.pixiv.site-key}")
    private String siteKey;
    @Value("${mikoto.pixiv.rsa.public}")
    private String publicKey;
    @Value("${mikoto.pixiv.rsa.private}")
    private String privateKey;
    @Value("${mikoto.pixiv.secret}")
    private String secret;

    @Qualifier("captchaService")
    private CaptchaService captchaService;
    @Qualifier("userRepository")
    private UserRepository userRepository;

    @RequestMapping(
            value = "/sso/doRegister",
            method = RequestMethod.POST,
            produces = "application/json;charset=UTF-8"
    )
    public JSONObject register(@RequestBody String jsonText) throws NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, IOException, InvalidKeyException {
        JSONObject outputJson = new JSONObject();
        JSONObject inputJson = JSONObject.parseObject(jsonText);

        if (captchaService.verify(secret, inputJson.getString(RE_CAPTCHA_RESPONSE))) {
            User user = new User();
            user.setCreateTime(new Date());
            user.setUserName(decrypt(inputJson.getString(USER_NAME), getPrivateKey(privateKey)));
            user.setUserSalt(getRandomString(10));
            user.setUserPassword(
                    getSha256(
                            decrypt(inputJson.getString(USER_PASSWORD), getPrivateKey(privateKey)) +
                                    "|MIKOTO_PIXIV_SSO|" +
                                    user.getUserSalt() +
                                    "|LOVE YOU FOREVER, Lin."
                    )
            );
            user.setUpdateTime(new Date());
            userRepository.saveAndFlush(user);
            outputJson.fluentPut("success", true);
            outputJson.fluentPut("msg", "");
        } else {
            outputJson.fluentPut("success", false);
            outputJson.fluentPut("msg", "Cannot verify your reCaptcha response.");
        }

        return outputJson;
    }

    public Object request() {
        return SaOAuth2Handle.serverRequest();
    }

    public void setSaOauth2Config(@NotNull SaOAuth2Config config) {
        config
                .setNotLoginView(() -> {
                    ModelAndView modelAndView = new ModelAndView("login");
                    modelAndView.addObject("publicKey", publicKey);
                    modelAndView.addObject("siteKey", siteKey);
                    return modelAndView;
                })
                .setDoLoginHandle((encryptedUserName, encryptedUserRawPassword) -> {
                    try {
                        String reCaptchaResponse = SaHolder.getRequest().getParam("reCaptchaResponse");

                        if (captchaService.verify(secret, reCaptchaResponse)) {
                            String userName = decrypt(encryptedUserName, getPrivateKey(privateKey));
                            String userRawPassword = decrypt(encryptedUserRawPassword, getPrivateKey(privateKey));
                            User user = userRepository.getUserByUserName(userName);

                            if (user == null) {
                                return SaResult.error("No such a user!");
                            }

                            String userPassword = userRawPassword +
                                    "|MIKOTO_PIXIV_SSO|" +
                                    user.getUserSalt() +
                                    "|LOVE YOU FOREVER, Lin.";

                            if (user.getUserPassword().equals(userPassword)) {
                                StpUtil.login(user.getUserId());
                                return SaResult.ok("Login success!").setData(StpUtil.getTokenValue());
                            }
                        }
                        return SaResult.error("Login failed");
                    } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException |
                             IllegalBlockSizeException | BadPaddingException | IOException |
                             InvalidKeySpecException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}