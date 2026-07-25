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

    /**
     * The locale request-param AND cookie name — a single contract shared by the
     * {@code ?lang=} switch, the persisting cookie, the language switcher
     * ({@code GlobalModelAdvice.langSwitchBase}) and the sign-on locale handler.
     */
    public static final String LOCALE_PARAM = "lang";
    /** Basename of the UI message bundles (messages.properties + _en/_ja/_zh). */
    private static final String MESSAGES_BASENAME = "classpath:messages";
    private static final String MESSAGES_ENCODING = "UTF-8";
    /** Cookie path scoping the locale to the whole app. */
    private static final String COOKIE_PATH = "/";

    @Bean
    LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver(LOCALE_PARAM);
        resolver.setDefaultLocale(Locale.US);
        resolver.setCookiePath(COOKIE_PATH);
        return resolver;
    }

    @Bean
    LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName(LOCALE_PARAM);   // ?lang=ja_JP switches + persists via the cookie
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
        ms.setBasename(MESSAGES_BASENAME);
        ms.setDefaultEncoding(MESSAGES_ENCODING);
        ms.setFallbackToSystemLocale(false);
        return ms;
    }
}
