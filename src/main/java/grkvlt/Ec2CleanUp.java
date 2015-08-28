/*
 * Copyright 2013 by Andrew Kennedy
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package grkvlt;

import java.util.List;
import java.util.Set;

import org.jclouds.ContextBuilder;
import org.jclouds.ec2.EC2Api;
import org.jclouds.ec2.domain.KeyPair;
import org.jclouds.ec2.domain.Reservation;
import org.jclouds.ec2.domain.RunningInstance;
import org.jclouds.ec2.domain.SecurityGroup;
import org.jclouds.ec2.domain.Tag;
import org.jclouds.ec2.domain.Volume;
import org.jclouds.ec2.features.TagApi;
import org.jclouds.ec2.features.ElasticBlockStoreApi;
import org.jclouds.ec2.features.InstanceApi;
import org.jclouds.ec2.features.KeyPairApi;
import org.jclouds.ec2.features.SecurityGroupApi;
import org.jclouds.ec2.util.TagFilterBuilder;
import org.jclouds.javax.annotation.Nullable;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.rest.RestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Module;

/**
 * Utility to clean up AWS EC2 security groups and keypairs
 *
 * @author Andrew Kennedy
 * @version 0.1.0-SNAPSHOT
 */
public class Ec2CleanUp {
    private static final Logger LOG = LoggerFactory.getLogger(Ec2CleanUp.class);

    /** Amazon Europe Region. */
    private static final String AWS_EUROPE = "eu-west-1";
    /** Default jclouds object name pattern. */
    private static final String JCLOUDS_NAME_REGEXP = "jclouds#.*";
    /** System property for AWS EC2 access key. */
    private static final String IDENTITY_PROPERTY = "aws-ec2.identity";
    /** System property for AWS EC2 secret key. */
    private static final String CREDENTIAL_PROPERTY = "aws-ec2.credential";

    private final String region;
    private final String regexp;
    private final String identity;
    private final String credential;
    private final boolean check;

    /**
     * Initialise the region and regular expressions.
     */
    public Ec2CleanUp(String region, String regexp, String identity, String credential, boolean check) {
        this.region = region;
        this.regexp = regexp;
        this.identity = identity;
        this.credential = credential;
        this.check = check;
    }

    /**
     * Perform the cleanup.
     */
    public void cleanUp() throws Exception {
        EC2Api api = getEC2Api();
        try {
            deleteKeyPairs(api.getKeyPairApi().orNull(), api.getInstanceApi().orNull());
            deleteSecurityGroups(api.getSecurityGroupApi().orNull());

            Optional<? extends TagApi> tagApi = api.getTagApiForRegion(region);
            if (tagApi.isPresent()) {
                deleteVolumes(api.getElasticBlockStoreApi().orNull(), tagApi.get());
            } else {
                LOG.info("No tag API, not " + (check ? "checking" : "cleaning") + " volumes");
            }
        } catch (Exception e) {
            LOG.error("{}", e.getMessage());
            System.exit(1);
        } finally {
            api.close();
        }
    }

    /**
     * Delete all matching {@link KeyPair}s.
     */
    public void deleteKeyPairs(KeyPairApi keyPairApi, InstanceApi instanceApi) throws Exception {
        Preconditions.checkNotNull(keyPairApi, "keyPairApi");
        Preconditions.checkNotNull(instanceApi, "instanceApi");

        Set<KeyPair> keys = keyPairApi.describeKeyPairsInRegion(region);
        Set<? extends Reservation<? extends RunningInstance>> instances = instanceApi.describeInstancesInRegion(region);
        Iterable<String> filtered = Iterables.filter(Iterables.transform(keys, new Function<KeyPair, String>() {
            public String apply(@Nullable KeyPair input) {
                return input.getKeyName();
            }
        }), Predicates.containsPattern("^" + regexp + "$"));
        LOG.info("Found {} matching KeyPairs", Iterables.size(filtered));

        if (!check) {
            int deleted = 0;

            for (String name : filtered) {
                boolean notInUse = true;

                for (Reservation<? extends RunningInstance> reservation : instances) {
                    for ( RunningInstance instance : reservation) {
                        notInUse &= (!name.equals(instance.getKeyName()));
                    }
                }

                if (notInUse)
                    try {
                        keyPairApi.deleteKeyPairInRegion(region, name);
                        deleted++;
                    } catch (Exception e) {
                        if (e.getMessage() != null && e.getMessage().contains("RequestLimitExceeded")) {
                            Thread.sleep(1000l); // Avoid triggering rate-limiter again
                        }
                        LOG.warn("Error deleting KeyPair '{}': {}", name, e.getMessage());
                    }
            }
            LOG.info("Deleted {} KeyPairs", deleted);
        }
    }

    /**
     * Delete all matching {@link SecurityGroup}s.
     */
    public void deleteSecurityGroups(SecurityGroupApi securityGroupApi) throws Exception {
        Preconditions.checkNotNull(securityGroupApi, "securityGroupApi");

        Set<SecurityGroup> groups = securityGroupApi.describeSecurityGroupsInRegion(region);
        Iterable<String> filtered = Iterables.filter(Iterables.transform(groups, new Function<SecurityGroup, String>() {
            public String apply(@Nullable SecurityGroup input) {
                return input.getName();
            }
        }), Predicates.containsPattern("^" + regexp + "$"));
        LOG.info("Found {} matching SecurityGroups", Iterables.size(filtered));

        if (!check) {
            int deleted = 0;
            for (String name : filtered) {
                try {
                    securityGroupApi.deleteSecurityGroupInRegion(region, name);
                    deleted++;
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("RequestLimitExceeded")) {
                        Thread.sleep(1000l); // Avoid triggering rate-limiter again
                    }
                    LOG.warn("Error deleting SecurityGroup '{}': {}", name, e.getMessage());
                }
            }
            LOG.info("Deleted {} SecurityGroups", deleted);
        }
    }

    public void deleteVolumes(ElasticBlockStoreApi ebsApi, TagApi tagApi) throws Exception {
        Preconditions.checkNotNull(ebsApi, "ebsApi");
        Preconditions.checkNotNull(tagApi, "tagApi");

        Iterable<String> filtered = "".equals(regexp)
                ? getVolumesWithNoName(ebsApi, tagApi)
                : getVolumesMatchingName(tagApi, regexp);

        if (!check) {
            int deleted = 0;
            for (String id : filtered) {
                try {
                    ebsApi.deleteVolumeInRegion(region, id);
                    deleted++;
                } catch (Exception e) {
                    if (e.getMessage() != null && e.getMessage().contains("RequestLimitExceeded")) {
                        Thread.sleep(1000l); // Avoid triggering rate-limiter again
                    }
                    LOG.warn("Error deleting Volume '{}': {}", id, e.getMessage());
                }
            }
            LOG.info("Deleted {} Volumes", deleted);
        }
    }

    private Iterable<String> getVolumesWithNoName(ElasticBlockStoreApi ebsApi, TagApi tagApi) {
        Set<String> namedVolumes = tagApi.filter(new TagFilterBuilder().volume().key("Name").build())
                .transform(new Function<Tag, String>() {
                    @Override public String apply(Tag input) {
                        return input.getResourceId();
                    }
                })
                .toSet();
        Set<String> allVolumes = FluentIterable.from(ebsApi.describeVolumesInRegion(region))
                .transform(new Function<Volume, String>() {
                    @Override
                    public String apply(Volume input) {
                        if (input.getId() == null && LOG.isTraceEnabled()) LOG.trace("No id on volume: " + input);
                        return input.getId() != null ? input.getId() : null;
                    }})
                .filter(Predicates.notNull())
                .toSet();
        Set<String> unnamedVolumes = Sets.difference(allVolumes, namedVolumes);
        LOG.info("Found {} unnamed Volumes", Iterables.size(unnamedVolumes));
        return unnamedVolumes;
    }

    private Iterable<String> getVolumesMatchingName(TagApi tagApi, final String name) {
        FluentIterable<Tag> volumeTags = tagApi.filter(new TagFilterBuilder()
                .volume()
                .key("Name")
                .build());
        LOG.info("Found {} named Volumes", Iterables.size(volumeTags));

        FluentIterable<String> filtered = volumeTags
                .filter(new Predicate<Tag>() {
                    @Override
                    public boolean apply(Tag input) {
                        return Predicates.containsPattern("^" + name + "$").apply(input.getValue().orNull());
                    }})
                .transform(new Function<Tag, String>() {
                    @Override
                    public String apply(Tag input) {
                        return input.getResourceId();
                    }});
        LOG.info("Found {} matching Volumes", Iterables.size(filtered));
        return filtered;
    }

    /**
     * Create the jclouds EC2 API object.
     */
    public EC2Api getEC2Api() throws Exception {
        ImmutableSet<Module> modules = ImmutableSet.<Module>of(new SLF4JLoggingModule());
        EC2Api api = ContextBuilder
                .newBuilder("aws-ec2")
                .credentials(identity, credential)
                .modules(modules)
                .buildApi(EC2Api.class);
        return api;
    }

    /**
     * Command-line entry point.
     * <p>
     * See {@code README.md} for usage example.
     */
    public static void main(String...argv) throws Exception {
        String regionParam = AWS_EUROPE;
        String regexpParam = JCLOUDS_NAME_REGEXP;
        boolean checkParam = Boolean.FALSE;

        // Set check, region and regular expression parameters from command line arguments
        List<String> parameters = Lists.newArrayList(argv);
        if (parameters.remove("check")) {
            checkParam = Boolean.TRUE;
        }
        if (parameters.size() > 0) regionParam = parameters.get(0);
        if (parameters.size() > 1) regexpParam = parameters.get(1);
        LOG.info("{} SecurityGroups, KeyPairs and Volumes in aws-ec2:{} matching '{}'",
                new Object[] { checkParam ? "Checking" : "Cleaning", regionParam, regexpParam });

        // Set EC2 identity and credential from system properties
        String identityValue = System.getProperty(IDENTITY_PROPERTY);
        String credentialValue = System.getProperty(CREDENTIAL_PROPERTY);

        Preconditions.checkNotNull(identityValue, String.format("The %s property must be set to your EC2 access key", IDENTITY_PROPERTY));
        Preconditions.checkNotNull(credentialValue, String.format("The %s property must be set to your EC2 secret key", CREDENTIAL_PROPERTY));

        // Initialise and then execute the cleanUp method
        Ec2CleanUp cleaner = new Ec2CleanUp(regionParam, regexpParam, identityValue, credentialValue, checkParam);
        cleaner.cleanUp();
    }

}
