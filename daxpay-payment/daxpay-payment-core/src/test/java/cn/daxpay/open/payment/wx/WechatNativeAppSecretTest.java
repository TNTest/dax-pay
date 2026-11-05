package cn.daxpay.open.payment.wx;

import cn.daxpay.open.payment.auth.channel.WechatAuthStrategy;
import cn.daxpay.open.payment.auth.core.AppScopeEnum;
import cn.daxpay.open.payment.auth.core.AuthSession;
import cn.daxpay.open.payment.unipay.param.assist.AuthCodeParam;
import cn.daxpay.open.payment.unipay.param.assist.GenerateAuthUrlParam;
import cn.daxpay.open.payment.wx.convert.merchant.WxMchAppConvert;
import cn.daxpay.open.payment.wx.entity.merchant.WxMchApp;
import cn.daxpay.open.payment.wx.facade.WxAppFacade;
import cn.daxpay.open.payment.wx.facade.WxAppView;
import cn.daxpay.open.payment.wx.param.merchant.MchWxMchAppParam;
import cn.daxpay.open.payment.wx.param.merchant.WxMchAppParam;
import cn.daxpay.open.platform.capability.wechat.auth.result.WechatAuthResult;
import cn.daxpay.open.platform.capability.wechat.auth.service.WechatMpAuthService;
import cn.daxpay.open.platform.core.exception.BizInfoException;
import cn.daxpay.open.platform.core.validation.ValidationGroup;
import jakarta.validation.Validation;
import jakarta.validation.groups.Default;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatNativeAppSecretTest {

    @Test
    void operatorCanAddNativeAppWithoutSecret() {
        var param = new WxMchAppParam().setMchNo("M-test").setAppName("Native扫码")
                .setAppType("mini_program").setWxAppId("wx-native-test");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(param, Default.class, ValidationGroup.add.class).isEmpty());
            param.setWxAppId("");
            assertFalse(factory.getValidator().validate(param, Default.class, ValidationGroup.add.class).isEmpty());
        }
    }

    @Test
    void merchantCanAddNativeAppWithoutSecret() {
        var param = new MchWxMchAppParam().setAppName("Native扫码")
                .setAppType("mini_program").setWxAppId("wx-native-test");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(param, Default.class, ValidationGroup.add.class).isEmpty());
        }
    }

    @Test
    void blankSecretOnEditKeepsExistingSecret() {
        var param = JsonMapper.builder().build().readValue("{\"appSecret\":\"  \"}", WxMchAppParam.class);
        var existing = new WxMchApp().setAppSecret("existing-secret");
        assertNull(param.getAppSecret());
        WxMchAppConvert.CONVERT.copy(param, existing);
        assertEquals("existing-secret", existing.getAppSecret());
    }

    @Test
    void oauthUrlRequiresSecretBeforeCallingWechat() {
        var auth = mock(WechatMpAuthService.class);
        var facade = mock(WxAppFacade.class);
        when(facade.getById(AppScopeEnum.MERCHANT, 1L)).thenReturn(appWithoutSecret());
        var strategy = new WechatAuthStrategy(auth, null, facade);
        var param = new GenerateAuthUrlParam().setAppScope("merchant").setAppRefId(1L);
        var error = assertThrows(BizInfoException.class,
                () -> strategy.generateAuthUrl(param, "test-token", new AuthSession()));
        assertEquals("error.channel.wechat.appSecretRequired", error.getMessageKey());
        verifyNoInteractions(auth);
    }

    @Test
    void oauthCallbackRequiresSecretBeforeCallingWechat() {
        var auth = mock(WechatMpAuthService.class);
        var facade = mock(WxAppFacade.class);
        when(facade.getById(AppScopeEnum.MERCHANT, 1L)).thenReturn(appWithoutSecret());
        var strategy = new WechatAuthStrategy(auth, null, facade);
        var session = new AuthSession().setAppScope("merchant").setAppRefId(1L);
        var error = assertThrows(BizInfoException.class, () -> strategy.doAuth(new AuthCodeParam(), session));
        assertEquals("error.channel.wechat.appSecretRequired", error.getMessageKey());
        verifyNoInteractions(auth);
    }

    @Test
    void oauthCallbackWithSecretStillUsesExistingFlow() {
        var auth = mock(WechatMpAuthService.class);
        var facade = mock(WxAppFacade.class);
        when(facade.getById(AppScopeEnum.MERCHANT, 1L)).thenReturn(
                new WxAppView(AppScopeEnum.MERCHANT, 1L, "wx-auth-test", "official_account", "secret", "OAuth"));
        when(auth.getTokenAndOpenId("code", "wx-auth-test", "secret"))
                .thenReturn(new WechatAuthResult().setOpenId("test-openid"));
        var strategy = new WechatAuthStrategy(auth, null, facade);
        var session = new AuthSession().setAppScope("merchant").setAppRefId(1L);
        assertEquals("test-openid", strategy.doAuth(new AuthCodeParam().setAuthCode("code"), session).getOpenId());
    }

    private WxAppView appWithoutSecret() {
        return new WxAppView(AppScopeEnum.MERCHANT, 1L, "wx-native-test", "mini_program", null, "Native扫码");
    }
}
