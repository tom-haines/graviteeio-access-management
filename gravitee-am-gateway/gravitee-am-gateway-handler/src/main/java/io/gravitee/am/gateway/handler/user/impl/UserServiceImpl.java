/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.am.gateway.handler.user.impl;

import io.gravitee.am.common.email.Email;
import io.gravitee.am.common.email.EmailBuilder;
import io.gravitee.am.common.jwt.Claims;
import io.gravitee.am.common.jwt.JWT;
import io.gravitee.am.common.oidc.StandardClaims;
import io.gravitee.am.gateway.handler.auth.idp.IdentityProviderManager;
import io.gravitee.am.gateway.handler.email.EmailService;
import io.gravitee.am.gateway.handler.jwt.JwtBuilder;
import io.gravitee.am.gateway.handler.jwt.JwtParser;
import io.gravitee.am.gateway.handler.user.UserService;
import io.gravitee.am.identityprovider.api.DefaultUser;
import io.gravitee.am.model.Domain;
import io.gravitee.am.model.User;
import io.gravitee.am.repository.management.api.UserRepository;
import io.gravitee.am.service.exception.UserAlreadyExistsException;
import io.gravitee.am.service.exception.UserNotFoundException;
import io.gravitee.am.service.exception.UserProviderNotFoundException;
import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Single;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_IDP_PREFIX = "default-idp-";

    @Value("${gateway.url:http://localhost:8092}")
    private String gatewayUrl;

    @Value("${user.resetPassword.email.subject:Please reset your password}")
    private String resetPasswordSubject;

    @Value("${user.resetPassword.token.expire-after:86400}")
    private Integer expireAfter;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtParser jwtParser;

    @Autowired
    private JwtBuilder jwtBuilder;

    @Autowired
    private Domain domain;

    @Autowired
    private EmailService emailService;

    @Autowired
    private IdentityProviderManager identityProviderManager;

    @Override
    public Maybe<User> verifyToken(String token) {
        return Maybe.fromCallable(() -> jwtParser.parse(token))
                .flatMap(jwt -> userRepository.findById(jwt.getSub()));
    }

    @Override
    public Single<User> register(User user) {
        final String source = DEFAULT_IDP_PREFIX + domain.getId();

        return userRepository.findByDomainAndUsernameAndSource(domain.getId(), user.getUsername(), source)
                .isEmpty()
                .map(isEmpty -> {
                    if (!isEmpty) {
                        throw new UserAlreadyExistsException(user.getUsername());
                    }
                    return true;
                })
                .flatMap(irrelevant -> identityProviderManager.getUserProvider(source)
                        .switchIfEmpty(Maybe.error(new UserProviderNotFoundException(source)))
                        .flatMapSingle(userProvider -> userProvider.create(convert(user)))
                        .flatMap(idpUser -> {
                            // AM 'users' collection is not made for authentication (but only management stuff)
                            // clear password
                            user.setPassword(null);
                            // set external id
                            user.setExternalId(idpUser.getId());
                            // set source
                            user.setSource(source);
                            // set domain
                            user.setDomain(domain.getId());
                            // internal user
                            user.setInternal(true);
                            // set date information
                            user.setCreatedAt(new Date());
                            user.setUpdatedAt(user.getCreatedAt());
                            return userRepository.create(user);
                        }));
    }

    @Override
    public Completable confirmRegistration(User user) {
        // user has completed his account, add it to the idp
        return identityProviderManager.getUserProvider(user.getSource())
                .flatMapSingle(userProvider -> userProvider.create(convert(user)))
                .flatMap(idpUser -> {
                    // update 'users' collection for management and audit purpose
                    user.setPassword(null);
                    user.setRegistrationCompleted(true);
                    user.setEnabled(true);
                    user.setExternalId(idpUser.getId());
                    user.setUpdatedAt(new Date());
                    return userRepository.update(user);
                })
                .toCompletable();
    }

    @Override
    public Completable resetPassword(User user) {
        // only idp manage password, find user idp and update its password
        return identityProviderManager.getUserProvider(user.getSource())
                .flatMapSingle(userProvider -> userProvider.update(user.getExternalId(), convert(user)))
                .flatMap(idpUser -> {
                    // update 'users' collection for management and audit purpose
                    user.setPassword(null);
                    user.setExternalId(idpUser.getId());
                    user.setUpdatedAt(new Date());
                    return userRepository.update(user);
                })
                .toCompletable();
    }

    @Override
    public Completable forgotPassword(String email) {
        return userRepository.findByDomainAndEmail(domain.getId(), email)
                .map(users -> users.stream().filter(user -> user.isInternal()).findFirst())
                .flatMapMaybe(optionalUser -> optionalUser.isPresent() ? Maybe.just(optionalUser.get()) : Maybe.empty())
                .switchIfEmpty(Maybe.error(new UserNotFoundException(email)))
                .map(user ->
                        convert(user,
                                resetPasswordSubject,
                                EmailBuilder.EmailTemplate.RESET_PASSWORD,
                                "/resetPassword",
                                "resetPasswordUrl"))
                .doOnSuccess(email1 -> new Thread(() -> emailService.send(email1)).start())
                .toSingle().toCompletable();

    }

    private Email convert(User user, String title, EmailBuilder.EmailTemplate template, String redirectUri, String redirectUriName) {
        Map<String, Object> params = prepareEmail(user, redirectUri, redirectUriName);
        Email email = new EmailBuilder()
                .to(user.getEmail())
                .subject(title)
                .template(template)
                .params(params)
                .build();
        return email;
    }

    private Map<String, Object> prepareEmail(User user, String redirectUri, String redirectUriName) {
        // generate a JWT to store user's information and for security purpose
        final Map<String, Object> claims = new HashMap<>();
        claims.put(Claims.iat, new Date().getTime() / 1000);
        claims.put(Claims.exp, new Date(System.currentTimeMillis() + (expireAfter * 1000)).getTime() / 1000);
        claims.put(Claims.sub, user.getId());
        claims.put(StandardClaims.EMAIL, user.getEmail());
        claims.put(StandardClaims.GIVEN_NAME, user.getFirstName());
        claims.put(StandardClaims.FAMILY_NAME, user.getLastName());

        String token = jwtBuilder.sign(new JWT(claims));

        String entryPoint = gatewayUrl;
        if (entryPoint != null && entryPoint.endsWith("/")) {
            entryPoint = entryPoint.substring(0, entryPoint.length() - 1);
        }

        String redirectUrl = entryPoint + "/" + user.getDomain() + redirectUri + "?token=" + token;

        Map<String, Object> params = new HashMap<>();
        params.put("user", user);
        params.put(redirectUriName, redirectUrl);
        params.put("token", token);

        return params;
    }

    private io.gravitee.am.identityprovider.api.User convert(User user) {
        DefaultUser idpUser = new DefaultUser(user.getUsername());
        idpUser.setCredentials(user.getPassword());

        Map<String, Object> additionalInformation = new HashMap<>();
        if (user.getFirstName() != null) {
            additionalInformation.put(StandardClaims.GIVEN_NAME, user.getFirstName());
        }
        if (user.getLastName() != null) {
            additionalInformation.put(StandardClaims.FAMILY_NAME, user.getLastName());
        }
        if (user.getEmail() != null) {
            additionalInformation.put(StandardClaims.EMAIL, user.getEmail());
        }
        if (user.getAdditionalInformation() != null) {
            user.getAdditionalInformation().forEach((k, v) -> additionalInformation.putIfAbsent(k, v));
        }
        idpUser.setAdditionalInformation(additionalInformation);
        return idpUser;
    }

}
