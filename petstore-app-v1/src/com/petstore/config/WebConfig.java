package com.petstore.config;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.List;
import java.util.Locale;

/**
 * Storefront internationalization (restores the legacy multi-lingual store,
 * en/ja/zh). A cookie-backed {@link LocaleResolver} makes the chosen locale STICK
 * across pages; {@link LocaleChangeInterceptor} lets any request switch it with
 * {@code ?lang=ja_JP}. Localized UI text comes from {@code messages_*.properties};
 * localized catalog content comes from catalog-service (locale-split tables).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    public static final List<Locale> SUPPORTED = List.of(
            Locale.US, Locale.JAPAN, Locale.SIMPLIFIED_CHINESE);   // en_US, ja_JP, zh_CN

    @Bean
    LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("lang");
        resolver.setDefaultLocale(Locale.US);
        resolver.setCookiePath("/");
        return resolver;
    }

    @Bean
    LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");   // ?lang=ja_JP switches + persists via the cookie
        return interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }

    /** UI label bundles: messages_en.properties / _ja / _zh. */
    @Bean
    MessageSource messageSource() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        return ms;
    }
}
