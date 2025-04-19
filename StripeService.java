package com.example.nagoyameshi.service;


import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.nagoyameshi.repository.UserRepository;
import com.example.nagoyameshi.security.UserDetailsImpl;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import com.stripe.param.SubscriptionUpdateParams;
import com.stripe.param.checkout.SessionCreateParams;


@Service
public class StripeService {
private static final Logger logger = LoggerFactory.getLogger(StripeService.class);

@Value("${stripe.api-key}")
private String stripeApiKey;

private final UserService userService;

public StripeService (UserRepository userRepository, UserService userService) {
	this.userService = userService;
}


public String createStripeSession(UserDetailsImpl userDetailsImpl, HttpServletRequest httpServletRequest) { 
	Stripe.apiKey = stripeApiKey;
	String requestUrl = httpServletRequest.getRequestURL().toString();        
    
    SessionCreateParams params = 
    	    SessionCreateParams.builder()
    	        .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
    	        .addLineItem(
    	            SessionCreateParams.LineItem.builder()
    	                .setPriceData(
    	                    SessionCreateParams.LineItem.PriceData.builder()                            
    	                        .setCurrency("jpy")
    	                        .setUnitAmount(300L) // 月額300円
    	                        .setRecurring(
    	                            SessionCreateParams.LineItem.PriceData.Recurring.builder()
    	                                .setInterval(SessionCreateParams.LineItem.PriceData.Recurring.Interval.MONTH)
    	                                .build()
    	                        )
    	                        .setProductData(
    	                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
    	                                .setName("月額有料プラン")
    	                                .build()
    	                        )
    	                        .build()
    	                )
    	                .setQuantity(1L)
    	                .build()
    	        )
    	        .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
    	        .setSuccessUrl(requestUrl.replaceAll("/user/upgrade", "") + "/login?success=true")
    	        .setCancelUrl(requestUrl.replace("/user", ""))  
    	        .setCustomerEmail(userDetailsImpl.getUser().getEmail()) // ユーザーのメールアドレスを設定
    	        .putMetadata("userId", userDetailsImpl.getUser().getId().toString())
    	        .build();
    try {
    	Session session = Session.create(params);            
        return session.getId();
    } catch (StripeException e) {
        logger.error("Stripeセッションの作成に失敗しました", e);
        return "";
    }    
}

	// セッションからメールアドレスと顧客IDを取得し、UserServiceクラスを介してデータベースに登録
	public void processSessionCompleted(Event event) {
		Optional<StripeObject> optionalStripeObject = event.getDataObjectDeserializer().getObject();
		optionalStripeObject.ifPresentOrElse(stripeObject -> {
        Session session = (Session) stripeObject;
        
        System.out.println("StripeService：Session ID: " + session.getId());
        
        if ("complete".equals(session.getStatus())) {
            String customerId = session.getCustomer();
            System.out.println("StripeService：Customer ID: " + customerId);
            Map<String, String> metadata = session.getMetadata();
            if (metadata != null) {
                metadata.put("customerId", customerId);
                userService.saveCustomerIdAndUpgrade(metadata);
            } else {
                System.out.println("StripeService：Session Metadataがnullです。");
            }
        } else {
            System.out.println("StripeService：Sessionが完了していません。");
        }
    }, () -> {
        System.out.println("会員ページの更新処理が失敗しました。");
    });
  }   
	
	// クレジットカード情報の編集
	public String createUpdateCardSession(String stripeCustomerId, HttpServletRequest httpServletRequest) {
	    Stripe.apiKey = stripeApiKey;
	    String requestUrl = httpServletRequest.getRequestURL().toString();

	    String baseUrl = requestUrl.replaceAll("/create-update-card-session", "");  	

	    SessionCreateParams params = SessionCreateParams.builder()
	        .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
	        .setMode(SessionCreateParams.Mode.SETUP)
	        .setCustomer(stripeCustomerId)
	        .setSuccessUrl(baseUrl + "/user?success=true")
	        .setCancelUrl(baseUrl + "/user")
	        .build();
	    try {
	        Session session = Session.create(params);
	        return session.getId();
	    } catch (StripeException e) {
	        logger.error("Stripeセッションの作成に失敗しました", e);
	        return "";
	    }
	}
	
	public boolean cancelSubscription(String customerId) {
	    try {
	        // Stripe APIキーを設定
	        Stripe.apiKey = stripeApiKey;

	        Map<String, Object> params = new HashMap<>();
	        params.put("expand", Collections.singletonList("subscriptions"));
	        
	        // 顧客情報を取得
	        Customer customer = Customer.retrieve(customerId, params, null);
	        logger.info("取得した顧客情報: {}", customer);

	        // サブスクリプションの存在を確認
	        if (customer.getSubscriptions().getData().isEmpty()) {
	            logger.warn("サブスクリプションが見つかりません: {}", customerId);
	            return false;
	        }

	        // 対象のサブスクリプションを取得
	        Subscription subscription = customer.getSubscriptions().getData().get(0);

	        // サブスクリプションをキャンセル
	        subscription.update(
	            SubscriptionUpdateParams.builder()
	                .setCancelAtPeriodEnd(true)
	                .build()
	        );
	        logger.info("サブスクリプションが正常にキャンセルされました: {}", subscription.getId());
	        return true;

	    } catch (StripeException e) {
	        logger.error("Stripe APIエラー: 顧客ID: {}, エラーコード: {}, 詳細: {}", customerId, e.getCode(), e.getMessage(), e);
	        return false;
	    } catch (Exception e) {
	        logger.error("予期しないエラーが発生しました: 顧客ID: {}, 詳細: {}", customerId, e.getMessage(), e);
	        return false;
	    }
	 }
}
