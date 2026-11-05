package cn.daxpay.open.start;

import cn.daxpay.open.payment.common.context.MerchantContextLoader;
import cn.daxpay.open.payment.trade.runtime.bo.NormalPayExecutionResult;
import cn.daxpay.open.payment.trade.runtime.service.pay.normal.NormalPayService;
import cn.daxpay.open.payment.unipay.param.trade.pay.NormalPayParam;
import cn.daxpay.open.payment.unipay.result.trade.pay.NormalPayResult;
import cn.daxpay.open.platform.common.redis.lock.LockExecutor;
import cn.daxpay.open.platform.common.json.configuration.JacksonConfiguration;
import cn.daxpay.open.platform.common.json.util.JacksonUtil;
import tools.jackson.databind.json.JsonMapper;
import cn.daxpay.open.platform.core.exception.ValidationFailedException;
import cn.daxpay.open.plugin.easypay.controller.api.EasyPayApiV1Controller;
import cn.daxpay.open.plugin.easypay.dao.EasyPayOrderManager;
import cn.daxpay.open.plugin.easypay.entity.EasyPayCredential;
import cn.daxpay.open.plugin.easypay.entity.EasyPayOrder;
import cn.daxpay.open.platform.system.entity.config.platform.infra.PlatformUrlConfig;
import cn.daxpay.open.platform.system.service.config.infra.PlatformUrlConfigService;
import cn.daxpay.open.plugin.easypay.param.api.v1.EasyPaySubmitV1Param;
import cn.daxpay.open.plugin.easypay.param.api.v1.EasyPayCreateV1Param;
import cn.daxpay.open.plugin.easypay.param.api.v2.EasyPayCreateV2Param;
import cn.daxpay.open.plugin.easypay.param.api.v1.EasyPayH5PayParam;
import cn.daxpay.open.plugin.easypay.service.api.v2.EasyPayPayV2Service;
import cn.daxpay.open.plugin.easypay.service.api.EasyPayAssistService;
import cn.daxpay.open.plugin.easypay.service.api.v1.EasyPayPayV1Service;
import cn.daxpay.open.plugin.easypay.service.config.EasyPayCredentialService;
import cn.daxpay.open.plugin.easypay.util.EasyPayUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.validation.DataBinder;
import org.springframework.web.bind.annotation.RequestHeader;

import java.math.BigDecimal;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class EasyPayV1CashierTest {

    private static final String KEY = "test-md5-key";

    @BeforeAll
    static void initializeProductionJsonMapping() {
        if (JacksonUtil.getObjectMapper() == null) {
            var configuration = new JacksonConfiguration();
            var builder = JsonMapper.builder();
            configuration.jacksonCustomizer().customize(builder);
            var mapper = builder.build();
            JacksonUtil.setObjectMapper(mapper);
            configuration.ignoreNullObjectMapper(mapper);
        }
    }

    @Test
    void newApiFormBindsDeviceAndVerifiesIndependentMd5Vector() {
        var param = new EasyPaySubmitV1Param();
        var binder = new DataBinder(param);
        binder.bind(new MutablePropertyValues(Map.of(
                "pid", "123456", "device", "pc", "type", "wxpay", "out_trade_no", "USR1NOx",
                "notify_url", "https://merchant.example/notify", "return_url", "https://merchant.example/return",
                "name", "TUC1", "money", "1.00", "sign_type", "MD5")));
        assertFalse(binder.getBindingResult().hasErrors());
        assertEquals("pc", param.getDevice());
        assertEquals("USR1NOx", param.getOutTradeNo());
        // Frozen independently from go-epay's alphabetically sorted V1 request fields.
        assertTrue(EasyPayUtil.verifySignByMd5(param, "d6d6822165d2b870237f682dc490c462", KEY));
        param.setDevice("mobile");
        assertFalse(EasyPayUtil.verifySignByMd5(param, "d6d6822165d2b870237f682dc490c462", KEY));
    }

    @Test
    void cashierTokenCannotBeReusedAcrossOrdersOrCredentials() {
        String token = EasyPayUtil.cashierToken(1L, 2, "order-a", KEY);
        assertTrue(EasyPayUtil.verifyCashierToken(1L, 2, "order-a", KEY, token));
        assertFalse(EasyPayUtil.verifyCashierToken(2L, 2, "order-a", KEY, token));
        assertFalse(EasyPayUtil.verifyCashierToken(1L, 3, "order-a", KEY, token));
        assertFalse(EasyPayUtil.verifyCashierToken(1L, 2, "order-b", KEY, token));
        assertFalse(EasyPayUtil.verifyCashierToken(1L, 2, "order-a", "another-key", token));
        assertFalse(EasyPayUtil.verifyCashierToken(1L, 2, "order-a", KEY, null));
        assertFalse(EasyPayUtil.verifyCashierToken(1L, 2, "order-a", KEY, "short"));
    }

    @Test
    void signedRetriesOnlyReturnTheCallingAppsOrderAndNativeQr() throws Exception {
        var f = new Fixture();
        f.order.setPayBody("weixin://own-qr").setPcCallType("qr_code");
        var submit = new EasyPaySubmitV1Param();
        submit.setPid(f.order.getPid());
        submit.setOutTradeNo(f.order.getOutTradeNo());
        submit.setSign(EasyPayUtil.signByMd5(submit, KEY));
        assertEquals("https://gateway.example/easypay/v1/pay/123#token=" + f.token(), f.service.submit(submit));

        var create = new EasyPayCreateV1Param();
        create.setPid(submit.getPid());
        create.setOutTradeNo(submit.getOutTradeNo());
        create.setSign(EasyPayUtil.signByMd5(create, KEY));
        assertEquals("weixin://own-qr", f.service.create(create).getQrcode());

        var pair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        f.credential.setEnableV2(true).setUseSystemKey(false).setPlatformPrivateKey(privateKey)
                .setPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        var v2 = new EasyPayPayV2Service(f.credentials, f.assist, f.orders, null, null, null);
        var retry = new EasyPayCreateV2Param();
        retry.setPid(submit.getPid());
        retry.setType("wxpay");
        retry.setMethod("web");
        retry.setOutTradeNo(submit.getOutTradeNo());
        retry.setSign(EasyPayUtil.signByRsa(retry, privateKey));
        assertEquals("weixin://own-qr", v2.create(retry).getPayInfo());
        assertEquals(0, f.payCalls.get());
    }

    @Test
    void invalidTokenCannotReadOrStartPayment() {
        var f = new Fixture();
        assertThrows(ValidationFailedException.class, () -> f.service.cashierInfo(f.order.getId(), "invalid"));
        assertThrows(ValidationFailedException.class, () -> f.service.cashierPay(f.order.getId(), "invalid"));
        assertEquals(0, f.payCalls.get());
    }

    @Test
    void cashierUsesStoredAmountAndReusesConcurrentNativeRequest() throws Exception {
        var f = new Fixture();
        Callable<String> request = () -> f.service.cashierPay(f.order.getId(), f.token()).getPayBody();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(java.util.List.of(request, request));
            assertEquals("weixin://test-native-order", results.get(0).get());
            assertEquals("weixin://test-native-order", results.get(1).get());
        }
        assertEquals(1, f.payCalls.get());
        assertEquals(2, f.lockCalls.get());
        assertEquals(2345L, f.captured.getAmount());
        assertEquals("mch-test", f.captured.getMchNo());
        assertEquals("app-test", f.captured.getAppId());
        assertEquals("wechat_qr", f.captured.getMethod());
        assertEquals("merchant-order", f.captured.getBizOrderNo());
    }

    @Test
    void resultHidesMerchantReturnUntilPaidAndPaidOrderNeverRepays() {
        var f = new Fixture();
        assertNull(f.service.cashierInfo(f.order.getId(), f.token()).getReturnUrl());
        assertNull(f.service.cashierInfo(f.order.getId(), f.token()).getPayUrl());
        assertEquals(new BigDecimal("23.45"), f.service.cashierInfo(f.order.getId(), f.token()).getMoney());
        f.order.setStatus(1);
        var info = f.service.cashierPay(f.order.getId(), f.token());
        assertEquals("https://merchant.example/return", info.getReturnUrl());
        assertEquals(0, f.payCalls.get());
    }

    @Test
    void v2InternalEndpointsCannotReadV1OrdersEvenWithACachedQrCode() {
        var f = new Fixture();
        f.order.setPayBody("weixin://cached-secret-qr");
        assertThrows(ValidationFailedException.class, () -> f.assist.findSubmitInfo(f.order.getId()));
        assertThrows(ValidationFailedException.class, () -> f.assist.queryOrderStatus(f.order.getId()));
        var v2 = new EasyPayPayV2Service(null, f.assist, f.orders, null, null, null);
        var param = new EasyPayH5PayParam();
        param.setId(f.order.getId());
        assertThrows(ValidationFailedException.class, () -> v2.submitPayByH5(param));
        // Actual V2 rows keep the old internal-read behavior.
        f.order.setApiVersion("v2");
        assertEquals("weixin://cached-secret-qr", f.assist.findSubmitInfo(f.order.getId()).getPayBody());
    }

    @Test
    void legacyInternalReadEndpointsRequireTheSameBearerHeader() throws Exception {
        for (String name : new String[]{"orderInfo", "queryOrderStatus", "cashierInfo", "cashierPay"}) {
            var method = EasyPayApiV1Controller.class.getMethod(name, Long.class, String.class);
            var header = method.getParameters()[1].getAnnotation(RequestHeader.class);
            assertNotNull(header);
            assertEquals("X-EasyPay-Token", header.value());
            assertTrue(header.required());
        }
    }

    private static class Fixture {
        final EasyPayOrder order = new EasyPayOrder();
        final EasyPayCredential credential = new EasyPayCredential();
        final EasyPayCredentialService credentials;
        final AtomicInteger payCalls = new AtomicInteger();
        final AtomicInteger lockCalls = new AtomicInteger();
        volatile NormalPayParam captured;
        final EasyPayPayV1Service service;
        final EasyPayOrderManager orders;
        final EasyPayAssistService assist;

        Fixture() {
            order.setId(123L);
            order.setPid(456);
            order.setMchNo("mch-test");
            order.setAppId("app-test");
            order.setOutTradeNo("merchant-order");
            order.setName("test purchase");
            order.setMoney(2345L);
            order.setType("wxpay");
            order.setStatus(0);
            order.setApiVersion("v1");
            order.setReturnUrl("https://merchant.example/return");
            credential.setPid(456).setEnable(true).setEnableV1(true).setMd5Key(KEY).setAppId(order.getAppId());
            credential.setMchNo(order.getMchNo());
            credentials = new EasyPayCredentialService(null, null, null, null, null, null) {
                @Override public EasyPayCredential getAndCheck(Integer pid) {
                    assertEquals(456, pid);
                    return credential;
                }
            };
            orders = new EasyPayOrderManager() {
                @Override public Optional<EasyPayOrder> findByOutTradeNo(String outTradeNo) {
                    // A second application uses the same order number, with its own QR code.
                    return Optional.of(new EasyPayOrder().setPid(789).setAppId("another-app")
                            .setOutTradeNo(outTradeNo).setPayBody("weixin://foreign-qr").setPcCallType("qr_code"));
                }
                @Override public Optional<EasyPayOrder> findByAppIdAndOutTradeNo(String appId, String outTradeNo) {
                    return order.getAppId().equals(appId) && order.getOutTradeNo().equals(outTradeNo)
                            ? Optional.of(order) : Optional.empty();
                }
                @Override public Optional<EasyPayOrder> findByIdNotTenant(Long id) {
                    return order.getId().equals(id) ? Optional.of(order) : Optional.empty();
                }
                @Override public int updateById(EasyPayOrder entity) {
                    assertSame(order, entity);
                    return 1;
                }
            };
            var normalPay = new NormalPayService(null, null, null, null, null, null, null, null, null) {
                @Override public NormalPayExecutionResult payInternal(NormalPayParam param) {
                    payCalls.incrementAndGet();
                    captured = param;
                    return new NormalPayExecutionResult().setContainerId(789L)
                            .setResult(new NormalPayResult().setOrderNo("kernel-order")
                                    .setPayBody("weixin://test-native-order").setPayBodyType("qr_code"));
                }
            };
            var lock = new LockExecutor(null) {
                @Override public synchronized <T> T execute(String key, long expire, long wait,
                        Supplier<T> action, Supplier<? extends RuntimeException> fail) {
                    assertTrue(key.contains("epay-v1-123"));
                    lockCalls.incrementAndGet();
                    return action.get();
                }
            };
            var context = new MerchantContextLoader(null, null, null, null) {
                @Override public void initMch(String mchNo) { assertEquals("mch-test", mchNo); }
            };
            assist = new EasyPayAssistService(orders, context, null, null);
            var urls = new PlatformUrlConfigService(null) {
                @Override public PlatformUrlConfig getUrlConfig() {
                    return new PlatformUrlConfig().setPaymentGatewayBaseUrl("https://gateway.example");
                }
            };
            service = new EasyPayPayV1Service(credentials, assist, orders, normalPay, urls, lock, context);
        }

        String token() { return EasyPayUtil.cashierToken(order.getId(), order.getPid(), order.getOutTradeNo(), KEY); }
    }
}
