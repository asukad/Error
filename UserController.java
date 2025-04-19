package com.example.nagoyameshi.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.nagoyameshi.entity.Role;
import com.example.nagoyameshi.entity.User;
import com.example.nagoyameshi.form.UserEditForm;
import com.example.nagoyameshi.repository.RoleRepository;
import com.example.nagoyameshi.repository.UserRepository;
import com.example.nagoyameshi.repository.VerificationTokenRepository;
import com.example.nagoyameshi.security.UserDetailsImpl;
import com.example.nagoyameshi.service.StripeService;
import com.example.nagoyameshi.service.UserService;

@Controller
@RequestMapping("/user")
public class UserController {
    private final UserRepository userRepository;    
    private final UserService userService;
    private final VerificationTokenRepository verificationTokenRepository;    
    private final StripeService stripeService;
    private final RoleRepository roleRepository;
    
    public UserController(UserRepository userRepository, UserService userService, VerificationTokenRepository verificationTokenRepository,
    		StripeService stripeService, RoleRepository roleRepository) {
        this.userRepository = userRepository;    
        this.userService = userService; 
        this.verificationTokenRepository= verificationTokenRepository;
        this.stripeService = stripeService;
        this.roleRepository = roleRepository;
    }    
    
    // 会員情報ページ
    @GetMapping
    public String index(@AuthenticationPrincipal UserDetailsImpl userDetailsImpl, @RequestParam(value = "success", required = false) String success, Model model) {         
        User user = userRepository.getReferenceById(userDetailsImpl.getUser().getId());  
        
        model.addAttribute("user", user);
        model.addAttribute("isPremiumUser", isPremiumUser(user));
        
        if ("true".equals(success)) {
            model.addAttribute("successMessage", "クレジットカード情報を変更しました。");
        }
        
        return "user/index";
    }

    private boolean isPremiumUser(User user) {
        return user.getRole() != null && "ROLE_PREMIUM".equals(user.getRole().getName());
    }
    
    // 会員情報編集ページ
    @GetMapping("/edit")
    public String edit(@AuthenticationPrincipal UserDetailsImpl userDetailsImpl, Model model) {        
        User user = userRepository.getReferenceById(userDetailsImpl.getUser().getId());           
        
        UserEditForm userEditForm = new UserEditForm(user.getId(), user.getEmail(), user.getName(), user.getFurigana(), user.getPhoneNumber(),
        		user.getAddress(), user.getAge(), user.getOccupation(), user.getStripeCustomerId());
                
        model.addAttribute("userEditForm", userEditForm);        
        model.addAttribute("isPremiumUser", isPremiumUser(user));
        model.addAttribute("stripeCustomerId", user.getStripeCustomerId());
        
        return "user/edit";
    }   
    
    // 会員情報更新
    @PostMapping("/update")
    public String update(@ModelAttribute @Validated UserEditForm userEditForm, BindingResult bindingResult, 
                         RedirectAttributes redirectAttributes, HttpServletRequest httpServletRequest, Model model) {
        
    	if (userService.isEmailChanged(userEditForm) && userService.isEmailRegistered(userEditForm.getEmail())) {
            FieldError fieldError = new FieldError(bindingResult.getObjectName(), "email", "すでに登録済みのメールアドレスです。");
            bindingResult.addError(fieldError);                       
        }
        
        if (bindingResult.hasErrors()) {
            User user = userRepository.getReferenceById(userEditForm.getId());
            model.addAttribute("isPremiumUser", isPremiumUser(user));
            return "user/edit";
        }    

        userService.update(userEditForm);
        redirectAttributes.addFlashAttribute("successMessage", "会員情報を編集しました。");
        
        return "redirect:/user";
    }
    
    // 有料会員登録    
    @PostMapping("/upgrade")
    public String upgrade(@AuthenticationPrincipal UserDetailsImpl userDetailsImpl, 
    		HttpServletRequest httpServletRequest, Model model) {   
    	
    	User user = userRepository.getReferenceById(userDetailsImpl.getUser().getId());      	
        
        model.addAttribute("user", user);        
        String sessionId = stripeService.createStripeSession(userDetailsImpl, httpServletRequest);
                
        model.addAttribute("sessionId", sessionId);     
        model.addAttribute("userId", user.getId());        
                
        return "user/upgrade";
    } 
    
 
    @PostMapping("/updateRole")
    public String updateRole(@AuthenticationPrincipal UserDetailsImpl userDetailsImpl) {
        User user = userRepository.getReferenceById(userDetailsImpl.getUser().getId());
        Role role = roleRepository.findByName("c");
        
        user.setRole(role);
        userRepository.save(user);
        
        return "redirect:/user/profile";
    }
    
    
    // クレジットカード情報変更  
    @PostMapping("/update-card")
    public String updateCard(@AuthenticationPrincipal UserDetailsImpl userDetailsImpl, HttpServletRequest httpServletRequest, Model model) {
        User user = userRepository.getReferenceById(userDetailsImpl.getUser().getId());
        model.addAttribute("user", user);
        String sessionId = stripeService.createUpdateCardSession(user.getStripeCustomerId(), httpServletRequest);
        model.addAttribute("sessionId", sessionId);
        return "user/updateCard";
    }
        
   
    @GetMapping("/downgrade")
    public String showDowngradePage(Model model, @AuthenticationPrincipal UserDetailsImpl userDetailsImpl) {
        String stripeCustomerId = userDetailsImpl.getUser().getStripeCustomerId();
        if (stripeCustomerId == null || stripeCustomerId.isEmpty()) {
            stripeCustomerId = "未登録";
        }
        model.addAttribute("stripeCustomerId", stripeCustomerId);
        return "user/downgrade";
    }

    @PostMapping("/cancel")
    public String cancelSubscription(@AuthenticationPrincipal UserDetailsImpl userDetailsImpl, RedirectAttributes redirectAttributes) {
        User user = userDetailsImpl.getUser();
        System.out.println("Starting subscription cancellation for user: {}");

        try {        	
            List<Subscription> subscriptions = stripeService.getSubscriptions(user.getStripeCustomerId());
            System.out.println(subscriptions.size());

            stripeService.cancelSubscriptions(subscriptions);
            System.out.println("Subscriptions cancelled");

            String defaultPaymentMethodId = stripeService.getDefaultPaymentMethodId(user.getStripeCustomerId());
            System.out.println("Default payment method ID: {}");

            stripeService.detachPaymentMethodFromCustomer(defaultPaymentMethodId);
            System.out.println("Payment method detached");

            // Update the user's membership status in the database
            user.setRole(roleRepository.findByName("ROLE_FREE"));
            userRepository.save(user);
            System.out.println("User role updated to ROLE_FREE");

        } catch (StripeException e) {
        	e.printStackTrace(); // スタックトレース情報の出力
        	
        	System.out.println("Stripe API error during cancellation: ");
            redirectAttributes.addFlashAttribute("errorMessage", "有料プランの解約に失敗しました。再度お試しください。");
            return "redirect:/error-page";  // Change to appropriate error page or message handling
        }

        redirectAttributes.addFlashAttribute("successMessage", "有料プランの解約が完了しました。");
        return "redirect:/profile";  // Redirect to user's profile or appropriate page
    }

    
 // 退会
    @Transactional
    @PostMapping("/delete")
    public String delete(@RequestParam("userId") Integer userId, HttpServletRequest request, HttpServletResponse response, RedirectAttributes redirectAttributes) {     
        // verification_tokensテーブルの関連データを削除
        verificationTokenRepository.deleteByUserId(userId);
        
        // usersテーブルのデータを削除
        userRepository.deleteById(userId);
                
        redirectAttributes.addFlashAttribute("successMessage", "会員情報を削除しました。");
        
        // ログアウト処理
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null) {
            new SecurityContextLogoutHandler().logout(request, response, auth);
        }
        
        return "redirect:/";
    }        
    
}

