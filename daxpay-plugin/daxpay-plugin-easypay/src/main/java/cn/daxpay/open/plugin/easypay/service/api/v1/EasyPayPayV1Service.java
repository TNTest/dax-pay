package cn.daxpay.open.plugin.easypay.service.api.v1;

import cn.daxpay.open.payment.common.context.MerchantContextLoader;
import cn.daxpay.open.payment.common.lock.TradeLockKeys;
import cn.daxpay.open.payment.trade.runtime.bo.NormalPayExecutionResult;
import cn.daxpay.open.payment.trade.runtime.service.pay.normal.NormalPayService;
import cn.daxpay.open.payment.unipay.param.trade.pay.NormalPayParam;
import cn.daxpay.open.payment.unipay.result.trade.pay.NormalPayResult;
import cn.daxpay.open.platform.common.spring.util.WebServletUtil;
import cn.daxpay.open.platform.common.redis.lock.LockExecutor;
import cn.daxpay.open.platform.core.exception.DataNotExistException;
import cn.daxpay.open.platform.core.exception.ValidationFailedException;
import cn.daxpay.open.plugin.easypay.result.api.EasyPaySubmitInfoResult;
import cn.daxpay.open.platform.core.enums.unipay.PayBodyTypeEnum;
import cn.daxpay.open.platform.system.service.config.infra.PlatformUrlConfigService;
import cn.daxpay.open.plugin.easypay.dao.EasyPayOrderManager;
import cn.daxpay.open.plugin.easypay.entity.EasyPayOrder;
import cn.daxpay.open.plugin.easypay.enums.EasyPayApiVersionEnum;
import cn.daxpay.open.plugin.easypay.param.api.v1.EasyPayCreateV1Param;
import cn.daxpay.open.plugin.easypay.param.api.v1.EasyPaySubmitV1Param;
import cn.daxpay.open.plugin.easypay.result.api.v1.EasyPayCreateV1Result;
import cn.daxpay.open.plugin.easypay.service.api.EasyPayAssistService;
import cn.daxpay.open.plugin.easypay.service.config.EasyPayCredentialService;
import cn.daxpay.open.plugin.easypay.util.EasyPayUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/// # 易支付 V1 支付
///
@Slf4j
@Service
@RequiredArgsConstructor
public class EasyPayPayV1Service {

    private final EasyPayCredentialService easyPayCredentialService;
    private final EasyPayAssistService easyPayAssistService;
    private final EasyPayOrderManager easyPayOrderManager;
    private final NormalPayService normalPayService;
    private final PlatformUrlConfigService platformUrlConfigService;
    private final LockExecutor lockExecutor;
    private final MerchantContextLoader merchantContextLoader;

    /// 页面跳转支付 → 收银台 URL
    public String submit(EasyPaySubmitV1Param param) {
        var credential = easyPayCredentialService.getAndCheck(param.getPid());
        easyPayAssistService.checkSignV1(param, credential, param.getSign());
        String gateway = platformUrlConfigService.getUrlConfig().getPaymentGatewayBaseUrl();
        var existing = easyPayOrderManager.findByAppIdAndOutTradeNo(credential.getAppId(), param.getOutTradeNo());
        if (existing.isPresent()) {
            var order = existing.get();
            if (!Objects.equals(order.getPid(), credential.getPid())) {
                throw new ValidationFailedException("error.plugin.easypay.orderNotFound");
            }
            return cashierUrl(gateway, order, credential.getMd5Key());
        }
        var order = newOrder(param.getOutTradeNo(), param.getType(), param.getName(), param.getMoney(),
                param.getNotifyUrl(), param.getReturnUrl(), param.getParam(), param.getClientip(),
                credential);
        order.setPayUrl(gateway + "/easypay/v1/pay/" + order.getId())
                .setPcCallType(PayBodyTypeEnum.QR_CODE.getCode());
        easyPayOrderManager.save(order);
        return cashierUrl(gateway, order, credential.getMd5Key());
    }

    private String cashierUrl(String gateway, EasyPayOrder order, String md5Key) {
        String url = gateway + "/easypay/v1/pay/" + order.getId();
        if ("wxpay".equals(order.getType())) {
            // Fragment 不会进入 HTTP Referer 或 Nginx 查询日志。
            url += "#token=" + EasyPayUtil.cashierToken(order.getId(), order.getPid(), order.getOutTradeNo(), md5Key);
        }
        return url;
    }

    /// 经过单据访问凭证校验的收银台信息与轮询响应。
    public EasyPaySubmitInfoResult cashierInfo(Long id, String token) {
        return cashierResult(requireCashierOrder(id, token));
    }

    /// 金额、商户、应用与支付方式仅从已验签的协议订单装载。
    public EasyPaySubmitInfoResult cashierPay(Long id, String token) {
        requireCashierOrder(id, token);
        return lockExecutor.execute(TradeLockKeys.gatewayPay("epay-v1-" + id),
                TradeLockKeys.LONG_EXPIRE, TradeLockKeys.LONG_WAIT,
                () -> {
                    var order = requireCashierOrder(id, token);
                    if (!"wxpay".equals(order.getType())) {
                        throw new ValidationFailedException("error.plugin.easypay.unsupportedType");
                    }
                    if (Objects.equals(order.getStatus(), 1) || StrUtil.isNotBlank(order.getPayBody())) {
                        return cashierResult(order);
                    }
                    merchantContextLoader.initMch(order.getMchNo());
                    NormalPayParam payParam = new NormalPayParam();
                    payParam.setMchNo(order.getMchNo());
                    payParam.setAppId(order.getAppId());
                    payParam.setBizOrderNo(order.getOutTradeNo());
                    payParam.setTitle(order.getName());
                    payParam.setAmount(order.getMoney());
                    payParam.setMethod(easyPayAssistService.resolvePayMethod(order.getType(), "web", "pc", null));
                    payParam.setClientIp(order.getClientIp());
                    payParam.setSource(easyPayAssistService.sourceCode());
                    var execution = normalPayService.payInternal(payParam);
                    var result = execution.getResult();
                    order.setOrderId(execution.getContainerId())
                            .setTradeNo(result.getOrderNo())
                            .setPayBody(result.getPayBody())
                            .setPayUrl(result.getPayBody())
                            .setPcCallType(result.getPayBodyType());
                    easyPayOrderManager.updateById(order);
                    return cashierResult(order);
                }, () -> new ValidationFailedException("pay.error.pay.processing"));
    }

    private EasyPayOrder requireCashierOrder(Long id, String token) {
        var order = easyPayOrderManager.findByIdNotTenant(id)
                .orElseThrow(() -> new DataNotExistException("error.plugin.easypay.orderNotFound"));
        if (!Objects.equals(order.getApiVersion(), EasyPayApiVersionEnum.V1.getCode())) {
            throw new ValidationFailedException("error.plugin.easypay.signInvalid");
        }
        var credential = easyPayCredentialService.getAndCheck(order.getPid());
        if (!Boolean.TRUE.equals(credential.getEnableV1())
                || !EasyPayUtil.verifyCashierToken(order.getId(), order.getPid(), order.getOutTradeNo(), credential.getMd5Key(), token)) {
            throw new ValidationFailedException("error.plugin.easypay.signInvalid");
        }
        return order;
    }

    private EasyPaySubmitInfoResult cashierResult(EasyPayOrder order) {
        return new EasyPaySubmitInfoResult()
                .setId(order.getId())
                .setType(order.getType())
                .setName(order.getName())
                .setMoney(EasyPayUtil.fenToYuan(order.getMoney()))
                .setStatus(order.getStatus())
                .setPayBody(order.getPayBody())
                .setPcCallType(order.getPcCallType())
                .setReturnUrl(Objects.equals(order.getStatus(), 1) ? order.getReturnUrl() : null);
    }

    /// 统一下单（mapi.php）
    public EasyPayCreateV1Result create(EasyPayCreateV1Param param) {
        var credential = easyPayCredentialService.getAndCheck(param.getPid());
        easyPayAssistService.checkSignV1(param, credential, param.getSign());
        EasyPayCreateV1Result result = new EasyPayCreateV1Result();
        try {
            EasyPayOrder order;
            var optional = easyPayOrderManager.findByAppIdAndOutTradeNo(credential.getAppId(), param.getOutTradeNo());
            if (optional.isPresent()) {
                order = optional.get();
                if (StrUtil.isNotBlank(order.getPayBody()) || StrUtil.isNotBlank(order.getPayUrl())) {
                    return fillResult(result, order);
                }
            } else {
                order = newOrder(param.getOutTradeNo(), param.getType(), param.getName(), param.getMoney(),
                        param.getNotifyUrl(), param.getReturnUrl(), param.getParam(),
                        StrUtil.blankToDefault(param.getClientip(), WebServletUtil.getClientIp()),
                        credential);
                easyPayOrderManager.save(order);
            }
            String payMethod = easyPayAssistService.resolvePayMethod(param.getType(), "web", param.getDevice(), null);
            if (easyPayAssistService.needCashier(payMethod)) {
                String gateway = platformUrlConfigService.getUrlConfig().getPaymentGatewayBaseUrl();
                order.setPayUrl(gateway + "/easypay/v1/h5/" + order.getId());
                easyPayOrderManager.updateById(order);
                return fillResult(result, order);
            }
            NormalPayParam payParam = new NormalPayParam();
            payParam.setMchNo(credential.getMchNo());
            payParam.setAppId(credential.getAppId());
            payParam.setBizOrderNo(order.getOutTradeNo());
            payParam.setTitle(order.getName());
            payParam.setAmount(order.getMoney());
            payParam.setMethod(payMethod);
            payParam.setClientIp(order.getClientIp());
            payParam.setSource(easyPayAssistService.sourceCode());
            NormalPayExecutionResult execution = normalPayService.payInternal(payParam);
            NormalPayResult payResult = execution.getResult();
            order.setOrderId(execution.getContainerId())
                    .setTradeNo(payResult.getOrderNo())
                    .setPayBody(payResult.getPayBody())
                    .setPayUrl(payResult.getPayBody())
                    .setPcCallType(payResult.getPayBodyType());
            easyPayOrderManager.updateById(order);
            return fillResult(result, order);
        } catch (Exception e) {
            log.error("易支付V1下单失败", e);
            result.setCode(-1).setMsg(e.getMessage());
            return result;
        }
    }

    /// 组装 V1 下单成功响应
    private EasyPayCreateV1Result fillResult(EasyPayCreateV1Result result, EasyPayOrder order) {
        result.setCode(0).setMsg("success").setTradeNo(order.getTradeNo());
        String body = StrUtil.blankToDefault(order.getPayBody(), order.getPayUrl());
        String callType = order.getPcCallType();
        if (ObjectsEqualsQr(callType) || StrUtil.isBlank(order.getPayBody())) {
            result.setQrcode(body);
            result.setPayurl(order.getPayUrl());
        } else {
            result.setPayurl(body);
        }
        return result;
    }

    private boolean ObjectsEqualsQr(String callType) {
        return PayBodyTypeEnum.QR_CODE.getCode().equals(callType)
                || PayBodyTypeEnum.LINK.getCode().equals(callType);
    }

    /// 创建协议订单实体
    private EasyPayOrder newOrder(String outTradeNo, String type, String name, String money,
                                  String notifyUrl, String returnUrl, String param, String clientIp,
                                  cn.daxpay.open.plugin.easypay.entity.EasyPayCredential credential) {
        EasyPayOrder order = new EasyPayOrder();
        order.setId(IdUtil.getSnowflakeNextId());
        order.setOutTradeNo(outTradeNo);
        order.setType(type);
        order.setName(name);
        // 协议入参为元字符串, 落库统一转为分
        order.setMoney(EasyPayUtil.yuanToFen(money));
        order.setRefundMoney(0L);
        order.setPid(credential.getPid());
        order.setAppId(credential.getAppId());
        order.setMchNo(credential.getMchNo());
        order.setApiVersion(EasyPayApiVersionEnum.V1.getCode());
        order.setAddTime(OffsetDateTime.now(ZoneOffset.UTC));
        order.setParam(param);
        order.setClientIp(StrUtil.blankToDefault(clientIp, WebServletUtil.getClientIp()));
        order.setNotifyUrl(notifyUrl);
        order.setReturnUrl(returnUrl);
        order.setStatus(0);
        return order;
    }
}
