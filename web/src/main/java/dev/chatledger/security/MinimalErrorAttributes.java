package dev.chatledger.security;

import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an error response may say: the status and its standard reason phrase. Nothing else. The
 * framework also adds the request path and a timestamp whatever {@code server.error.include-path} says;
 * the path is attacker-controlled text and neither helps the user, so both are left out. Message,
 * exception and stack trace are already off in the properties; this makes the minimum a rule of the code
 * and not only of the configuration.
 */
@Component
class MinimalErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> all = super.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());
        Map<String, Object> minimal = new LinkedHashMap<>();
        minimal.put("status", all.get("status"));
        minimal.put("error", all.get("error"));
        return minimal;
    }
}
