/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.identity.mgt.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.identity.mgt.AuthenticationContext;
import org.wso2.carbon.identity.mgt.Group;
import org.wso2.carbon.identity.mgt.IdentityStore;
import org.wso2.carbon.identity.mgt.User;
import org.wso2.carbon.identity.mgt.bean.GroupBean;
import org.wso2.carbon.identity.mgt.bean.UserBean;
import org.wso2.carbon.identity.mgt.claim.Claim;
import org.wso2.carbon.identity.mgt.claim.MetaClaim;
import org.wso2.carbon.identity.mgt.exception.AuthenticationFailure;
import org.wso2.carbon.identity.mgt.exception.DomainException;
import org.wso2.carbon.identity.mgt.exception.GroupNotFoundException;
import org.wso2.carbon.identity.mgt.exception.IdentityStoreClientException;
import org.wso2.carbon.identity.mgt.exception.IdentityStoreException;
import org.wso2.carbon.identity.mgt.exception.IdentityStoreServerException;
import org.wso2.carbon.identity.mgt.exception.UserNotFoundException;
import org.wso2.carbon.identity.mgt.impl.internal.IdentityMgtDataHolder;

import java.io.UnsupportedEncodingException;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.security.auth.callback.Callback;

import static org.wso2.carbon.identity.mgt.impl.util.IdentityMgtConstants.USERNAME_CLAIM;
import static org.wso2.carbon.kernel.utils.LambdaExceptionUtils.rethrowConsumer;
import static org.wso2.carbon.kernel.utils.StringUtils.isNullOrEmpty;

/**
 * Represents a virtual identity store to abstract the underlying stores.
 *
 * @since 1.0.0
 * <p>
 * TODO Add logic - uuid
 */
public class IdentityStoreImpl implements IdentityStore {

    private static final Logger log = LoggerFactory.getLogger(IdentityStoreImpl.class);

    private Map<String, Domain> domainMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private Map<Integer, Domain> domains = new HashMap<>();

    private SortedSet<Domain> sortedDomains = new TreeSet<>((d1, d2) -> {

        int d1Priority = d1.getOrder();
        int d2Priority = d2.getOrder();

        // Allow having multiple domains with the same priority
        if (d1Priority == d2Priority) {
            d2Priority++;
        }

        return Integer.compare(d1Priority, d2Priority);
    });

    public IdentityStoreImpl(List<Domain> domains) throws IdentityStoreException {

        if (domains == null || domains.isEmpty()) {
            throw new IdentityStoreException("No domains registered.");
        }

        this.sortedDomains.addAll(domains);
        domains.stream()
                .forEach(domain -> {
                    this.domains.put(domain.getId(), domain);
                    this.domainMap.put(domain.getName(), domain);
                });

        if (log.isDebugEnabled()) {
            log.debug("Identity store successfully initialized.");
        }
    }

    /**
     * Identity User Management Read Operations.
     */

    @Override
    public User getUser(String uniqueUserId) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        boolean userExists;
        try {
            userExists = domain.isUserExists(decodedUniqueUserId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to check the existence of the unique user - " +
                    "%s.", uniqueUserId), e);
        }

        if (!userExists) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        return new User.UserBuilder()
                .setUserId(uniqueUserId)
                .setDomainName(domain.getName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    @Override
    public User getUser(Claim claim) throws IdentityStoreException, UserNotFoundException {

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Provided claim is invalid.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetUser(claim, domain);
    }

    @Override
    public User getUser(Claim claim, String domainName) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getUser(claim);
        }

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetUser(claim, domain);
    }

    @Override
    public List<User> listUsers(int offset, int length) throws IdentityStoreException {

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListUsers(offset, length, domain);
    }

    @Override
    public List<User> listUsers(int offset, int length, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listUsers(offset, length);
        }

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListUsers(offset, length, domain);
    }

    @Override
    public List<User> listUsers(Claim claim, int offset, int length) throws IdentityStoreException {

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListUsers(claim, offset, length, domain);
    }

    @Override
    public List<User> listUsers(Claim claim, int offset, int length, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listUsers(claim, offset, length);
        }

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListUsers(claim, offset, length, domain);
    }

    @Override
    public List<User> listUsers(MetaClaim metaClaim, String filterPattern, int offset, int length)
            throws IdentityStoreException {

        if (metaClaim == null) {
            throw new IdentityStoreClientException("Invalid claim URI.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListUsers(metaClaim, filterPattern, offset, length, domain);
    }

    @Override
    public List<User> listUsers(MetaClaim metaClaim, String filterPattern, int offset, int length, String domainName)
            throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listUsers(metaClaim, filterPattern, offset, length);
        }

        if (metaClaim == null) {
            throw new IdentityStoreClientException("Invalid claim URI.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListUsers(metaClaim, filterPattern, offset, length, domain);
    }

    @Override
    public Group getGroup(String uniqueGroupId) throws IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid unique group id.");
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        boolean groupExists;
        try {
            groupExists = domain.isGroupExists(decodedUniqueGroupId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique group - " +
                    "%s.", uniqueGroupId), e);
        }

        if (!groupExists) {
            throw new GroupNotFoundException("Invalid unique user id.");
        }

        return new Group.GroupBuilder()
                .setGroupId(uniqueGroupId)
                .setDomainName(domain.getName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    @Override
    public Group getGroup(Claim claim) throws IdentityStoreException, GroupNotFoundException {

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doGetGroup(claim, domain);
    }

    @Override
    public Group getGroup(Claim claim, String domainName) throws IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(domainName)) {
            return getGroup(claim);
        }

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doGetGroup(claim, domain);
    }

    @Override
    public List<Group> listGroups(int offset, int length) throws IdentityStoreException {

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListGroups(offset, length, domain);
    }

    @Override
    public List<Group> listGroups(int offset, int length, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listGroups(offset, length);
        }

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListGroups(offset, length, domain);
    }

    @Override
    public List<Group> listGroups(Claim claim, int offset, int length) throws IdentityStoreException {

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListGroups(claim, offset, length, domain);
    }

    @Override
    public List<Group> listGroups(Claim claim, int offset, int length, String domainName) throws
            IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listGroups(claim, offset, length);
        }

        if (claim == null || isNullOrEmpty(claim.getValue())) {
            throw new IdentityStoreClientException("Invalid claim.");
        }

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListGroups(claim, offset, length, domain);
    }

    @Override
    public List<Group> listGroups(MetaClaim metaClaim, String filterPattern, int offset, int length) throws
            IdentityStoreException {

        if (metaClaim == null) {
            throw new IdentityStoreClientException("Invalid claim URI.");
        }

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        return doListGroups(metaClaim, filterPattern, offset, length, domain);
    }

    @Override
    public List<Group> listGroups(MetaClaim metaClaim, String filterPattern, int offset, int length, String
            domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return listGroups(metaClaim, filterPattern, offset, length);
        }

        if (metaClaim == null) {
            throw new IdentityStoreClientException("Invalid claim URI.");
        }

        if (offset < 0) {
            throw new IdentityStoreClientException("Invalid offset value.");
        }

        if (length == 0) {
            return Collections.emptyList();
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        return doListGroups(metaClaim, filterPattern, offset, length, domain);
    }

    @Override
    public List<Group> getGroupsOfUser(String uniqueUserId) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        boolean userExists;
        try {
            userExists = domain.isUserExists(decodedUniqueUserId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique user - " +
                    "%s.", uniqueUserId), e);
        }

        if (!userExists) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        List<String> domainGroupIds;
        try {
            domainGroupIds = domain.getGroupsOfUser(decodedUniqueUserId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to retrieve the unique group ids for user id" +
                    " - %s.", uniqueUserId), e);
        }

        if (domainGroupIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueGroupIds = new ArrayList<>();
        domainGroupIds.forEach(rethrowConsumer(domainGroupId -> uniqueGroupIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainGroupId))));

        return uniqueGroupIds.stream()
                .map(uniqueGroupId -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroupId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<User> getUsersOfGroup(String uniqueGroupId) throws IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid unique group id.");
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        boolean groupExists;
        try {
            groupExists = domain.isGroupExists(decodedUniqueGroupId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique group - " +
                    "%s.", uniqueGroupId), e);
        }

        if (!groupExists) {
            throw new GroupNotFoundException("Invalid unique group id.");
        }

        List<String> domainUserIds;
        try {
            domainUserIds = domain.getUsersOfGroup(decodedUniqueGroupId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to retrieve the unique group ids for user id" +
                    " - %s.", uniqueGroupId), e);
        }

        if (domainUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueUserIds = new ArrayList<>();
        domainUserIds.forEach(rethrowConsumer(domainUserId -> uniqueUserIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainUserId))));

        return uniqueUserIds.stream()
                .map(uniqueUserId -> new User.UserBuilder()
                        .setUserId(uniqueUserId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public boolean isUserInGroup(String uniqueUserId, String uniqueGroupId) throws IdentityStoreException,
            UserNotFoundException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueUserId) || isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid inputs.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);
        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        if (decodedUniqueUserId.getKey().intValue() != decodedUniqueGroupId.getKey().intValue()) {
            return false;
        }

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        boolean userExists;
        try {
            userExists = domain.isUserExists(decodedUniqueUserId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique user - " +
                    "%s.", uniqueUserId), e);
        }

        if (!userExists) {
            throw new UserNotFoundException("Invalid unique user id.");
        }

        boolean groupExists;
        try {
            groupExists = domain.isGroupExists(decodedUniqueGroupId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to check existence of unique group - " +
                    "%s.", uniqueGroupId), e);
        }

        if (!groupExists) {
            throw new GroupNotFoundException("Invalid unique group id.");
        }

        try {
            return domain.isUserInGroup(decodedUniqueUserId.getValue(), decodedUniqueGroupId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to check user %s in the group %s",
                    uniqueUserId, uniqueGroupId));
        }
    }

    @Override
    public List<Claim> getClaimsOfUser(String uniqueUserId) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            return domain.getClaimsOfUser(decodedUniqueUserId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to get claims of unique user - %s.",
                    uniqueUserId), e);
        }
    }

    @Override
    public List<Claim> getClaimsOfUser(String uniqueUserId, List<MetaClaim> metaClaims) throws IdentityStoreException,
            UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid unique user id.");
        }

        if (metaClaims == null || metaClaims.isEmpty()) {
            return Collections.emptyList();
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            return domain.getClaimsOfUser(decodedUniqueUserId.getValue(), metaClaims);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to get claims of unique user - %s.",
                    uniqueUserId), e);
        }
    }

    @Override
    public List<Claim> getClaimsOfGroup(String uniqueGroupId) throws IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid unique group id.");
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        try {
            return domain.getClaimsOfGroup(decodedUniqueGroupId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to get claims of unique goup - %s.",
                    uniqueGroupId), e);
        }
    }

    @Override
    public List<Claim> getClaimsOfGroup(String uniqueGroupId, List<MetaClaim> metaClaims) throws
            IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid unique group id.");
        }

        if (metaClaims == null || metaClaims.isEmpty()) {
            return Collections.emptyList();
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        try {
            return domain.getClaimsOfGroup(decodedUniqueGroupId.getValue(), metaClaims);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to get claims of unique group - %s.",
                    uniqueGroupId), e);
        }
    }

    /**
     * Identity User Management Read Operations End.
     */

    /**
     * Identity User Management Write Operations
     */

    @Override
    public User addUser(UserBean userBean) throws IdentityStoreException {

        if (userBean == null || (userBean.getClaims().isEmpty() && userBean.getCredentials().isEmpty())) {
            throw new IdentityStoreClientException("Invalid user.");
        }

        if (!userBean.getClaims().isEmpty() && !isUsernamePresent(userBean)) {
            throw new IdentityStoreClientException("Valid username claim must be present.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        String domainUserId;
        try {
            domainUserId = domain.addUser(userBean);
        } catch (DomainException e) {
            throw new IdentityStoreClientException("Failed to persist user.", e);
        }

        return new User.UserBuilder()
                .setUserId(getEncodedUniqueEntityId(domain.getId(), domainUserId))
                .setDomainName(domain.getName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    @Override
    public User addUser(UserBean userBean, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return addUser(userBean);
        }

        if (userBean == null || (userBean.getClaims().isEmpty() && userBean.getCredentials().isEmpty())) {
            throw new IdentityStoreClientException("Invalid user.");
        }

        if (!userBean.getClaims().isEmpty() && !isUsernamePresent(userBean)) {
            throw new IdentityStoreClientException("Valid username claim must be present.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        String domainUserId;
        try {
            domainUserId = domain.addUser(userBean);
        } catch (DomainException e) {
            throw new IdentityStoreClientException("Failed to persist user.", e);
        }

        return new User.UserBuilder()
                .setUserId(getEncodedUniqueEntityId(domain.getId(), domainUserId))
                .setDomainName(domain.getName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    @Override
    public List<User> addUsers(List<UserBean> userBeen) throws IdentityStoreException {

        if (userBeen == null || userBeen.isEmpty()) {
            throw new IdentityStoreClientException("Invalid user list.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        List<String> domainUserIds;
        try {
            domainUserIds = domain.addUsers(userBeen);
        } catch (DomainException e) {
            throw new IdentityStoreClientException("Failed to persist user.", e);
        }

        if (domainUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueUserIds = new ArrayList<>();
        domainUserIds.forEach(rethrowConsumer(domainUserId -> uniqueUserIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainUserId))));

        return uniqueUserIds.stream()
                .map(uniqueUserId -> new User.UserBuilder()
                        .setUserId(uniqueUserId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<User> addUsers(List<UserBean> userBeen, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return addUsers(userBeen);
        }

        if (userBeen == null || userBeen.isEmpty()) {
            throw new IdentityStoreClientException("Invalid user list.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        List<String> domainUserIds;
        try {
            domainUserIds = domain.addUsers(userBeen);
        } catch (DomainException e) {
            throw new IdentityStoreClientException("Failed to persist user.", e);
        }

        if (domainUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueUserIds = new ArrayList<>();
        domainUserIds.forEach(rethrowConsumer(domainUserId -> uniqueUserIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainUserId))));

        return uniqueUserIds.stream()
                .map(uniqueUserId -> new User.UserBuilder()
                        .setUserId(uniqueUserId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void updateUserClaims(String uniqueUserId, List<Claim> claims) throws IdentityStoreException,
            UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid user unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            domain.updateUserClaims(decodedUniqueUserId.getValue(), claims);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update claims of user - %s", uniqueUserId));
        }
    }

    @Override
    public void updateUserClaims(String uniqueUserId, List<Claim> claimsToAdd, List<Claim> claimsToRemove)
            throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid user unique id.");
        }

        if ((claimsToAdd == null || claimsToAdd.isEmpty()) && (claimsToRemove == null || claimsToRemove.isEmpty())) {
            return;
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            domain.updateUserClaims(decodedUniqueUserId.getValue(), claimsToAdd, claimsToRemove);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update claims of user - %s", uniqueUserId));
        }
    }

    @Override
    public void updateUserCredentials(String uniqueUserId, List<Callback> credentials) throws IdentityStoreException,
            UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid user unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            domain.updateUserCredentials(decodedUniqueUserId.getValue(), credentials);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update credentials of user - %s",
                    uniqueUserId));
        }

    }

    @Override
    public void updateUserCredentials(String uniqueUserId, List<Callback> credentialsToAdd, List<Callback>
            credentialsToRemove) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid user unique id.");
        }

        if ((credentialsToAdd == null || credentialsToAdd.isEmpty()) && (credentialsToRemove == null ||
                credentialsToRemove.isEmpty())) {
            return;
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            domain.updateUserCredentials(decodedUniqueUserId.getValue(), credentialsToAdd, credentialsToRemove);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update credentials of user - %s",
                    uniqueUserId));
        }
    }

    @Override
    public void deleteUser(String uniqueUserId) throws IdentityStoreException, UserNotFoundException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid user unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            domain.deleteUser(decodedUniqueUserId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to delete user - %s", uniqueUserId));
        }
    }

    @Override
    public void updateGroupsOfUser(String uniqueUserId, List<String> uniqueGroupIds) throws IdentityStoreException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid user unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        List<String> domainGroupIds = new ArrayList<>();

        if (uniqueGroupIds != null && !uniqueGroupIds.isEmpty()) {
            domainGroupIds = getDomainEntityIds(uniqueGroupIds, decodedUniqueUserId.getKey());
        }

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            domain.updateGroupsOfUser(decodedUniqueUserId.getValue(), domainGroupIds);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update groups of user - %s", uniqueUserId));
        }
    }

    @Override
    public void updateGroupsOfUser(String uniqueUserId, List<String> uniqueGroupIdsToAdd, List<String>
            uniqueGroupIdsToRemove) throws IdentityStoreException {

        if (isNullOrEmpty(uniqueUserId)) {
            throw new IdentityStoreClientException("Invalid user unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueUserId = getDecodedUniqueEntityId(uniqueUserId);

        List<String> domainGroupIdsToAdd = new ArrayList<>();
        if (uniqueGroupIdsToAdd != null && !uniqueGroupIdsToAdd.isEmpty()) {
            domainGroupIdsToAdd = getDomainEntityIds(uniqueGroupIdsToAdd, decodedUniqueUserId.getKey());
        }

        List<String> domainGroupIdsToRemove = new ArrayList<>();
        if (uniqueGroupIdsToRemove != null && !uniqueGroupIdsToRemove.isEmpty()) {
            domainGroupIdsToRemove = getDomainEntityIds(uniqueGroupIdsToRemove, decodedUniqueUserId.getKey());
        }

        Domain domain = domains.get(decodedUniqueUserId.getKey());

        try {
            domain.updateGroupsOfUser(decodedUniqueUserId.getValue(), domainGroupIdsToAdd, domainGroupIdsToRemove);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update groups of user - %s", uniqueUserId));
        }
    }

    @Override
    public Group addGroup(GroupBean groupBean) throws IdentityStoreException {

        if (groupBean == null || groupBean.getClaims().isEmpty()) {
            throw new IdentityStoreClientException("Invalid group.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving the primary domain.", e);
        }

        String domainGroupId;
        try {
            domainGroupId = domain.addGroup(groupBean);
        } catch (DomainException e) {
            throw new IdentityStoreClientException("Failed to persist group.", e);
        }

        return new Group.GroupBuilder()
                .setGroupId(getEncodedUniqueEntityId(domain.getId(), domainGroupId))
                .setDomainName(domain.getName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    @Override
    public Group addGroup(GroupBean groupBean, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return addGroup(groupBean);
        }

        if (groupBean == null || groupBean.getClaims().isEmpty()) {
            throw new IdentityStoreClientException("Invalid group.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        String domainGroupId;
        try {
            domainGroupId = domain.addGroup(groupBean);
        } catch (DomainException e) {
            throw new IdentityStoreClientException("Failed to persist group.", e);
        }

        return new Group.GroupBuilder()
                .setGroupId(getEncodedUniqueEntityId(domain.getId(), domainGroupId))
                .setDomainName(domain.getName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    @Override
    public List<Group> addGroups(List<GroupBean> groupBeen) throws IdentityStoreException {

        if (groupBeen == null || groupBeen.isEmpty()) {
            throw new IdentityStoreClientException("Invalid group list. Group list is null or empty.");
        }

        Domain domain;
        try {
            domain = getPrimaryDomain();
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Error while retrieving primary domain.", e);
        }

        List<String> domainGroupIds;
        try {
            domainGroupIds = domain.addGroups(groupBeen);
        } catch (DomainException e) {
            throw new IdentityStoreClientException("Failed to persist user.", e);
        }

        if (domainGroupIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueGroupIds = new ArrayList<>();
        domainGroupIds.forEach(rethrowConsumer(domainGroupId -> uniqueGroupIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainGroupId))));

        return uniqueGroupIds.stream()
                .map(uniqueGroupId -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroupId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public List<Group> addGroups(List<GroupBean> groupBeen, String domainName) throws IdentityStoreException {

        if (isNullOrEmpty(domainName)) {
            return addGroups(groupBeen);
        }

        if (groupBeen == null || groupBeen.isEmpty()) {
            throw new IdentityStoreClientException("Invalid group list. Group list is null or empty.");
        }

        Domain domain;
        try {
            domain = getDomainFromDomainName(domainName);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Error while retrieving domain from the domain name " +
                    "- %s", domainName), e);
        }

        List<String> domainGroupIds;
        try {
            domainGroupIds = domain.addGroups(groupBeen);
        } catch (DomainException e) {
            throw new IdentityStoreClientException("Failed to persist user.", e);
        }

        if (domainGroupIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueGroupIds = new ArrayList<>();
        domainGroupIds.forEach(rethrowConsumer(domainGroupId -> uniqueGroupIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainGroupId))));

        return uniqueGroupIds.stream()
                .map(uniqueGroupId -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroupId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    public void updateGroupClaims(String uniqueGroupId, List<Claim> claims) throws IdentityStoreException,
            GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid group unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        try {
            domain.updateGroupClaims(decodedUniqueGroupId.getValue(), claims);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update claims of group - %s",
                    uniqueGroupId));
        }
    }

    @Override
    public void updateGroupClaims(String uniqueGroupId, List<Claim> claimsToAdd, List<Claim> claimsToRemove) throws
            IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid group unique id.");
        }

        if ((claimsToAdd == null || claimsToAdd.isEmpty()) && (claimsToRemove == null || claimsToRemove.isEmpty())) {
            return;
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        try {
            domain.updateGroupClaims(decodedUniqueGroupId.getValue(), claimsToAdd, claimsToRemove);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update claims of group - %s",
                    uniqueGroupId));
        }
    }

    @Override
    public void deleteGroup(String uniqueGroupId) throws IdentityStoreException, GroupNotFoundException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid group unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        try {
            domain.deleteGroup(decodedUniqueGroupId.getValue());
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to delete user - %s", uniqueGroupId));
        }
    }

    @Override
    public void updateUsersOfGroup(String uniqueGroupId, List<String> uniqueUserIds) throws IdentityStoreException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid group unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        List<String> domainGroupIds = new ArrayList<>();

        if (uniqueUserIds != null && !uniqueUserIds.isEmpty()) {
            domainGroupIds = getDomainEntityIds(uniqueUserIds, decodedUniqueGroupId.getKey());
        }

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        try {
            domain.updateUsersOfGroup(decodedUniqueGroupId.getValue(), domainGroupIds);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update users of group - %s",
                    uniqueGroupId));
        }
    }

    @Override
    public void updateUsersOfGroup(String uniqueGroupId, List<String> uniqueUserIdsToAdd, List<String>
            uniqueUserIdsToRemove) throws IdentityStoreException {

        if (isNullOrEmpty(uniqueGroupId)) {
            throw new IdentityStoreClientException("Invalid group unique id.");
        }

        SimpleEntry<Integer, String> decodedUniqueGroupId = getDecodedUniqueEntityId(uniqueGroupId);

        List<String> domainUserIdsToAdd = new ArrayList<>();
        if (uniqueUserIdsToAdd != null && !uniqueUserIdsToAdd.isEmpty()) {
            domainUserIdsToAdd = getDomainEntityIds(uniqueUserIdsToAdd, decodedUniqueGroupId.getKey());
        }

        List<String> domainUserIdsToRemove = new ArrayList<>();
        if (uniqueUserIdsToRemove != null && !uniqueUserIdsToRemove.isEmpty()) {
            domainUserIdsToRemove = getDomainEntityIds(uniqueUserIdsToRemove, decodedUniqueGroupId.getKey());
        }

        Domain domain = domains.get(decodedUniqueGroupId.getKey());

        try {
            domain.updateUsersOfGroup(decodedUniqueGroupId.getValue(), domainUserIdsToAdd, domainUserIdsToRemove);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to update users of group - %s",
                    uniqueGroupId));
        }
    }

    /**
     * Identity User Management Write Operations End.
     */

    /**
     * Identity User Management Authentication Related Operations.
     */

    @Override
    public AuthenticationContext authenticate(Claim claim, Callback[] credentials, String domainName)
            throws AuthenticationFailure, IdentityStoreException {

        if (claim == null || isNullOrEmpty(claim.getValue()) || credentials == null || credentials.length == 0) {
            throw new AuthenticationFailure("Invalid user credentials.");
        }

        if (!isNullOrEmpty(domainName)) {

            Domain domain;
            try {
                domain = getDomainFromDomainName(domainName);
            } catch (DomainException e) {
                log.error(String.format("Error while retrieving domain from the domain name - %s", domainName), e);
                throw new AuthenticationFailure(String.format("Invalid domain name - %s.", domainName));
            }

            String domainUserId = domain.authenticate(claim, credentials);
            String uniqueUserId;
            try {
                uniqueUserId = getEncodedUniqueEntityId(domain.getId(), domainUserId);
            } catch (IdentityStoreException e) {
                throw new IdentityStoreServerException("Failed to build unique user id.");
            }

            return new AuthenticationContext(
                    new User.UserBuilder()
                            .setUserId(uniqueUserId)
                            .setIdentityStore(this)
                            .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                            .setDomainName(domain.getName())
                            .build());
        }

        for (Domain domain : sortedDomains) {
            if (domain.isClaimSupported(claim.getClaimUri())) {
                try {
                    String domainUserId = domain.authenticate(claim, credentials);
                    String uniqueUserId;
                    try {
                        uniqueUserId = getEncodedUniqueEntityId(domain.getId(), domainUserId);
                    } catch (IdentityStoreException e) {
                        throw new IdentityStoreServerException("Failed to build unique user id.");
                    }

                    return new AuthenticationContext(
                            new User.UserBuilder()
                                    .setUserId(uniqueUserId)
                                    .setIdentityStore(this)
                                    .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                                    .setDomainName(domain.getName())
                                    .build());
                } catch (AuthenticationFailure e) {
                    if (log.isDebugEnabled()) {
                        log.debug(String.format("Failed to authenticate user - %s from domain - %s", claim.getValue(),
                                domainName), e);
                    }
                }
            }
        }

        throw new AuthenticationFailure("Invalid user credentials.");
    }

    /**
     * Identity User Management Authentication Related Operations End.
     */

    /**
     * Identity User Management Domain Read Operations.
     */

    @Override
    public String getPrimaryDomainName() throws IdentityStoreException {

        Domain domain = sortedDomains.first();

        if (domain == null) {
            throw new IdentityStoreServerException("No domains registered.");
        }

        return domain.getName();
    }

    @Override
    public Set<String> getDomainNames() throws IdentityStoreException {

        Set<String> domainNames = domainMap.keySet();

        if (domainNames.isEmpty()) {
            throw new IdentityStoreServerException("No domains registered.");
        }

        return domainMap.keySet();
    }

    /**
     * Identity User Management Domain Read End.
     */

    /**
     * Identity User Management private methods.
     */

    /**
     * Get unique entity Id.
     *
     * @param domainId       domain id.
     * @param domainEntityId domain entity id.
     * @return encoded unique entity id.
     * @throws IdentityStoreServerException Identity Store Exception.
     */
    private String getEncodedUniqueEntityId(int domainId, String domainEntityId) throws IdentityStoreException {

        String uniqueEntityId = domainId + "." + domainEntityId;

        try {
            return Base64.getEncoder().encodeToString(uniqueEntityId.getBytes("utf-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IdentityStoreServerException("Failed to encode the unique entity id");
        }
    }

    /**
     * Get domain id and domain unique entity id.
     *
     * @param uniqueEntityId unique entity id.
     * @return domain and unique entity id.
     * @throws IdentityStoreException Identity Store Exception.
     */
    private SimpleEntry<Integer, String> getDecodedUniqueEntityId(String uniqueEntityId) throws
            IdentityStoreException {

        byte[] bytes = Base64.getDecoder().decode(uniqueEntityId);
        String decodedUniqueEntityId;
        try {
            decodedUniqueEntityId = new String(bytes, "utf-8");
        } catch (UnsupportedEncodingException e) {
            throw new IdentityStoreServerException("Failed to decode the unique entity id");
        }

        String[] decodedUniqueEntityIdParts = decodedUniqueEntityId.split("\\.", 2);
        if (decodedUniqueEntityIdParts.length != 2 || isNullOrEmpty(decodedUniqueEntityIdParts[0]) ||
                isNullOrEmpty(decodedUniqueEntityIdParts[1])) {
            throw new IdentityStoreClientException("invalid unique entity id.");
        }

        int domainId;
        try {
            domainId = Integer.parseInt(decodedUniqueEntityIdParts[0]);
        } catch (NumberFormatException e) {
            throw new IdentityStoreClientException("invalid unique entity id.");
        }

        if (!domains.containsKey(domainId)) {
            throw new IdentityStoreClientException("invalid unique entity id.");
        }

        return new SimpleEntry<>(domainId, decodedUniqueEntityIdParts[1]);
    }

    private User doGetUser(Claim claim, Domain domain) throws IdentityStoreException, UserNotFoundException {

        String domainUserId;
        try {
            domainUserId = domain.getDomainUserId(claim);
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to get the domain user id.");
        }

        return new User.UserBuilder()
                .setUserId(getEncodedUniqueEntityId(domain.getId(), domainUserId))
                .setDomainName(domain.getName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    private List<User> doListUsers(int offset, int length, Domain domain) throws IdentityStoreException {

        List<String> domainUserIds;
        try {
            domainUserIds = domain.listDomainUsers(offset, length);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to list users in the domain - %s", domain), e);
        }

        if (domainUserIds == null || domainUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueUserIds = new ArrayList<>();
        domainUserIds.forEach(rethrowConsumer(domainUserId -> uniqueUserIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainUserId))));

        return uniqueUserIds.stream()
                .map(uniqueUserId -> new User.UserBuilder()
                        .setUserId(uniqueUserId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<User> doListUsers(Claim claim, int offset, int length, Domain domain) throws IdentityStoreException {

        List<String> domainUserIds;
        try {
            domainUserIds = domain.listDomainUsers(claim, offset, length);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to list users in the domain - %s", domain), e);
        }

        if (domainUserIds == null || domainUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueUserIds = new ArrayList<>();
        domainUserIds.forEach(rethrowConsumer(domainUserId -> uniqueUserIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainUserId))));

        return uniqueUserIds.stream()
                .map(uniqueUserId -> new User.UserBuilder()
                        .setUserId(uniqueUserId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<User> doListUsers(MetaClaim metaClaim, String filterPattern, int offset, int length, Domain domain)
            throws IdentityStoreException {

        List<String> domainUserIds;
        try {
            domainUserIds = domain.listDomainUsers(metaClaim, filterPattern, offset, length);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to list users in the domain - %s", domain), e);
        }

        if (domainUserIds == null || domainUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueUserIds = new ArrayList<>();
        domainUserIds.forEach(rethrowConsumer(domainUserId -> uniqueUserIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainUserId))));

        return uniqueUserIds.stream()
                .map(uniqueUserId -> new User.UserBuilder()
                        .setUserId(uniqueUserId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private Group doGetGroup(Claim claim, Domain domain) throws IdentityStoreException, GroupNotFoundException {

        String domainGroupId;
        try {
            domainGroupId = domain.getDomainGroupId(claim);
        } catch (DomainException e) {
            throw new IdentityStoreServerException("Failed to get the domain group id.");
        }

        return new Group.GroupBuilder()
                .setGroupId(getEncodedUniqueEntityId(domain.getId(), domainGroupId))
                .setDomainName(domain.getName())
                .setIdentityStore(this)
                .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                .build();
    }

    private List<Group> doListGroups(int offset, int length, Domain domain) throws IdentityStoreException {

        List<String> domainGroupIds;
        try {
            domainGroupIds = domain.listDomainGroups(offset, length);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to list groups in the domain - %s", domain),
                    e);
        }

        if (domainGroupIds == null || domainGroupIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueGroupIds = new ArrayList<>();
        domainGroupIds.forEach(rethrowConsumer(domainGroupId -> uniqueGroupIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainGroupId))));

        return uniqueGroupIds.stream()
                .map(uniqueGroupId -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroupId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<Group> doListGroups(Claim claim, int offset, int length, Domain domain) throws IdentityStoreException {

        List<String> domainGroupIds;
        try {
            domainGroupIds = domain.listDomainGroups(claim, offset, length);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to list groups in the domain - %s", domain),
                    e);
        }

        if (domainGroupIds == null || domainGroupIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueGroupIds = new ArrayList<>();
        domainGroupIds.forEach(rethrowConsumer(domainGroupId -> uniqueGroupIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainGroupId))));

        return uniqueGroupIds.stream()
                .map(uniqueGroupId -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroupId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private List<Group> doListGroups(MetaClaim metaClaim, String filterPattern, int offset, int length, Domain domain)
            throws IdentityStoreException {

        List<String> domainGroupIds;
        try {
            domainGroupIds = domain.listDomainGroups(metaClaim, filterPattern, offset, length);
        } catch (DomainException e) {
            throw new IdentityStoreServerException(String.format("Failed to list groups in the domain - %s", domain),
                    e);
        }

        if (domainGroupIds == null || domainGroupIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> uniqueGroupIds = new ArrayList<>();
        domainGroupIds.forEach(rethrowConsumer(domainGroupId -> uniqueGroupIds
                .add(getEncodedUniqueEntityId(domain.getId(), domainGroupId))));

        return uniqueGroupIds.stream()
                .map(uniqueGroupId -> new Group.GroupBuilder()
                        .setGroupId(uniqueGroupId)
                        .setDomainName(domain.getName())
                        .setIdentityStore(this)
                        .setAuthorizationStore(IdentityMgtDataHolder.getInstance().getAuthorizationStore())
                        .build())
                .collect(Collectors.toList());
    }

    private Domain getPrimaryDomain() throws DomainException {

        Domain domain = sortedDomains.first();

        if (domain == null) {
            throw new DomainException("No domains registered.");
        }

        return domain;
    }

    private Domain getDomainFromDomainName(String domainName) throws DomainException {

        Domain domain = domainMap.get(domainName);

        if (domain == null) {
            throw new DomainException(String.format("Domain %s was not found", domainName));
        }

        return domain;
    }

    private boolean isUsernamePresent(UserBean userBean) {

        return userBean.getClaims().stream()
                .filter(claim -> USERNAME_CLAIM.equals(claim.getClaimUri()) && !isNullOrEmpty(claim.getValue()))
                .findAny()
                .isPresent();
    }

    private List<String> getDomainEntityIds(List<String> uniqueEntityIds, int domainId) throws
            IdentityStoreException {

        List<String> domainEntityIds = new ArrayList<>();
        uniqueEntityIds.forEach(rethrowConsumer(uniqueEntityId -> {
            SimpleEntry<Integer, String> decodedUniqueEntityId = getDecodedUniqueEntityId(uniqueEntityId);
            if (domainId != decodedUniqueEntityId.getKey()) {
                throw new IdentityStoreClientException("User and group must be in the same domain.");
            }
            domainEntityIds.add(decodedUniqueEntityId.getValue());
        }));

        return domainEntityIds;
    }
}
