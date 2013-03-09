/*
 * Copyright 2013 by Andrew Kennedy
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package grkvlt;

import java.util.Set;

import org.jclouds.ContextBuilder;
import org.jclouds.ec2.EC2AsyncClient;
import org.jclouds.ec2.EC2Client;
import org.jclouds.ec2.domain.KeyPair;
import org.jclouds.ec2.domain.SecurityGroup;
import org.jclouds.ec2.services.KeyPairClient;
import org.jclouds.ec2.services.SecurityGroupClient;
import org.jclouds.javax.annotation.Nullable;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.rest.RestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
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

    /**
     * Initialise the region and regular expressions.
     */
    public Ec2CleanUp(String region, String regexp, String identity, String credential) {
        this.region = region;
        this.regexp = regexp;
        this.identity = identity;
        this.credential = credential;
    }

    /**
     * Perform the cleanup.
     */
    public void cleanUp() throws Exception {
        RestContext<EC2Client, EC2AsyncClient> context = getContext();
        try {
            EC2Client api = context.getApi();

            deleteKeyPairs(api.getKeyPairServices());
            deleteSecurityGroups(api.getSecurityGroupServices());
        } catch (Exception e) {
            LOG.error("{}", e.getMessage());
            System.exit(1);
        } finally {
            context.close();
        }
    }

    /**
     * Delete all matching {@link KeyPair}s.
     */
    public void deleteKeyPairs(KeyPairClient keyPairApi) throws Exception {
        Set<KeyPair> keys = keyPairApi.describeKeyPairsInRegion(region);
        Iterable<String> filtered = Iterables.filter(Iterables.transform(keys, new Function<KeyPair, String>() {
            @Override
            public String apply(@Nullable KeyPair input) {
                return input.getKeyName();
            }
        }), new Predicate<String>() {
            @Override
            public boolean apply(@Nullable String input) {
                return input.matches(regexp);
            }
        });
        LOG.info("Found {} matching KeyPairs", Iterables.size(filtered));
        int deleted = 0;
        for (String name : filtered) {
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

    /**
     * Delete all matching {@link SecurityGroup}s.
     */
    public void deleteSecurityGroups(SecurityGroupClient securityGroupApi) throws Exception {
        Set<SecurityGroup> groups = securityGroupApi.describeSecurityGroupsInRegion(region);
        Iterable<String> filtered = Iterables.filter(Iterables.transform(groups, new Function<SecurityGroup, String>() {
            @Override
            public String apply(@Nullable SecurityGroup input) {
                return input.getName();
            }
        }), new Predicate<String>() {
            @Override
            public boolean apply(@Nullable String input) {
                return input.matches(regexp);
            }
        });
        LOG.info("Found {} matching SecurityGroups", Iterables.size(filtered));
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

    /**
     * Create a jclouds {@link RestContext} to access the EC2 API.
     */
    public RestContext<EC2Client, EC2AsyncClient> getContext() throws Exception {
        ImmutableSet<Module> modules = ImmutableSet.<Module>of(new SLF4JLoggingModule());
        RestContext<EC2Client, EC2AsyncClient> context = ContextBuilder
                .newBuilder("aws-ec2")
                .credentials(identity, credential)
                .modules(modules)
                .build();
        return context;
    }

    /**
     * Command-line entry point.
     *
     * See {@code README.md} for usage example.
     */
    public static void main(String...argv) throws Exception {
        String regionParam = AWS_EUROPE;
        String regexpParam = JCLOUDS_NAME_REGEXP;

        // Set region and regular expression for command line arguments
        if (argv.length > 0) regionParam = argv[0];
        if (argv.length > 1) regexpParam = argv[1];
        LOG.info("Cleaning SecurityGroups and KeyPairs in aws-ec2:{} matching '{}'", regionParam, regexpParam);

        // Set EC2 identity and credential from system properties
        String identityValue = System.getProperty(IDENTITY_PROPERTY);
        String credentialValue = System.getProperty(CREDENTIAL_PROPERTY);

        Preconditions.checkNotNull(identityValue, String.format("The %s property must be set to your EC2 access key", IDENTITY_PROPERTY));
        Preconditions.checkNotNull(credentialValue, String.format("The %s property must be set to your EC2 secret key", CREDENTIAL_PROPERTY));

        // Initialise and then execute the cleanUp method
        Ec2CleanUp cleaner = new Ec2CleanUp(regionParam, regexpParam, identityValue, credentialValue);
        cleaner.cleanUp();
    }

}
