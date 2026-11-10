package com.tripplanner.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripplanner.config.AppProperties;
import com.tripplanner.user.User;
import com.tripplanner.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoLoginTests {
    static AppProperties props() {
        return new AppProperties(new AppProperties.Jwt("test",7),new AppProperties.Kakao("rest-key",null,List.of("tripplanner://")),null,true,null,7,null);
    }
    @Test void loginAuthorizationOnlyRequestsOrdinaryLogin() {
        var client=new KakaoOAuthClient(null,props());
        var query=UriComponentsBuilder.fromUriString(client.authorizeUrl("https://example.invalid/callback","login-state")).build().getQueryParams();
        assertEquals("rest-key",query.getFirst("client_id"));
        assertEquals("login-state",query.getFirst("state"));
        assertEquals("code",query.getFirst("response_type"));
        assertFalse(query.containsKey("scope"));
    }
    @Test void exchangingLoginCodeStillLoadsNicknameAndVerifiedEmail() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
            .andExpect(content().formDataContains(Map.of("grant_type","authorization_code","code","one-time-code")))
            .andRespond(withSuccess("{\"access_token\":\"provider-access\",\"refresh_token\":\"unused-refresh\"}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
            .andExpect(header("Authorization","Bearer provider-access"))
            .andRespond(withSuccess("{\"id\":123,\"kakao_account\":{\"profile\":{\"nickname\":\"여행자\"},\"is_email_verified\":true,\"email\":\"test@example.invalid\"}}",MediaType.APPLICATION_JSON));
        var user=new KakaoOAuthClient(builder.build(),props()).fetchUser("one-time-code","https://example.invalid/callback");
        assertEquals("123",user.id());assertEquals("여행자",user.nickname());assertEquals("test@example.invalid",user.email());
        assertEquals(3,user.getClass().getRecordComponents().length);
        server.verify();
    }
    @Test void ordinaryLoginStillUpdatesUserAndIssuesTheAppSession() throws Exception {
        var user=new User("kakao:123",null,"이전 이름");ReflectionTestUtils.setField(user,"id",1L);
        var client=new KakaoOAuthClient(null,props()) {
            @Override public KakaoUser fetchUser(String code,String redirect) {
                assertEquals("login-code",code);assertEquals("callback",redirect);
                return new KakaoUser("123","새 이름","test@example.invalid");
            }
        };
        var users=(UserRepository)java.lang.reflect.Proxy.newProxyInstance(UserRepository.class.getClassLoader(),new Class<?>[]{UserRepository.class},(proxy,method,args)->{
            if(method.getName().equals("findByAuthSubject")) {assertEquals("kakao:123",args[0]);return Optional.of(user);}
            throw new UnsupportedOperationException(method.getName());
        });
        var encoder=new NimbusJwtEncoder(new ImmutableSecret<>(new byte[32]));
        var result=new AuthService(client,users,encoder,props()).loginWithKakao("login-code","callback");
        assertEquals("1",com.nimbusds.jwt.SignedJWT.parse(result.accessToken()).getJWTClaimsSet().getSubject());
        assertEquals("새 이름",result.user().name());assertEquals(1L,result.user().id());
        var json=new ObjectMapper().valueToTree(result.user());
        assertEquals(Set.of("id","email","name"),new HashSet<>(json.properties().stream().map(Map.Entry::getKey).toList()));
    }
}
