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
	
	//	顧客のデフォルトの支払方法のIDを取得する
	public String getDefaultPaymentMethodId(String customerId) throws StripeException {
		Customer customer = Customer.retrieve(customerId);
		return customer.getInvoiceSettings().getDefaultPaymentMethod();
	}
	
//	支払方法と顧客の紐づけを解除する
	public void detachPaymentMethodFromCustomer(String paymentMethodId) throws StripeException {
		PaymentMethod paymentMethod = PaymentMethod.retrieve(paymentMethodId);
		paymentMethod.detach();
	}
	
//	サブスクリプションを取得する
	public List<Subscription> getSubscriptions(String customerId) throws StripeException {
		Stripe.apiKey = stripeApiKey; // 追加
		
		SubscriptionListParams subscriptionListParams = 
			SubscriptionListParams.builder()
				.setCustomer(customerId)
				.build();
		
		return Subscription.list(subscriptionListParams).getData();
	}
	
//	サブスクリプションをキャンセルする
	public void cancelSubscriptions(List<Subscription> subscriptions) throws StripeException {
		Stripe.apiKey = stripeApiKey; // 追加
		for (Subscription subscription : subscriptions) {
			subscription.cancel();
		}
	}

}
