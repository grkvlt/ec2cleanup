package debug;

import grkvlt.Ec2CleanUp;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Set;

import javax.inject.Provider;

import org.jclouds.ContextBuilder;
import org.jclouds.aws.filters.FormSigner;
import org.jclouds.crypto.Crypto;
import org.jclouds.domain.Credentials;
import org.jclouds.encryption.bouncycastle.config.BouncyCastleCryptoModule;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpUtils;
import org.jclouds.http.internal.SignatureWire;
import org.jclouds.io.payloads.UrlEncodedFormPayload;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;

import com.google.common.base.Suppliers;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Providers;

public class SignatureTest {

    public static void main(String[] args) throws Exception {
        String identityValue = System.getProperty(Ec2CleanUp.IDENTITY_PROPERTY);
        String credentialValue = System.getProperty(Ec2CleanUp.CREDENTIAL_PROPERTY);
        System.out.println("JCE");
        testSignature(identityValue, credentialValue, false);
        System.out.println("BouncyCastle");
        testSignature(identityValue, credentialValue, true);
    }
        
    public static void testSignature(String identity, String credential, boolean bouncyCastle) throws Exception {
        Set<Module> modules = Sets.newLinkedHashSet();
        modules.add(new SLF4JLoggingModule());
        if (bouncyCastle) modules.add(new BouncyCastleCryptoModule());
        Injector injector = ContextBuilder
                .newBuilder("aws-ec2")
                .credentials(identity, credential)
                .modules(modules)
                .buildInjector();
            
        String apiVersion = "2012-06-01";
        Credentials credentials = new Credentials.Builder().identity(identity).credential(credential).build();
        Provider<String> dateService = Providers.of("2014-07-18T14%3A13%3A05.451Z");
        FormSigner formSigner = new FormSigner(injector.getInstance(SignatureWire.class), apiVersion, Suppliers.ofInstance(credentials), dateService, injector.getInstance(Crypto.class), injector.getInstance(HttpUtils.class));
        injector.getInstance(FormSigner.class);
            
        LinkedHashMultimap<String, String> headers = LinkedHashMultimap.create();
        headers.put("Host", "ec2.us-east-1.amazonaws.com");

        LinkedHashMultimap<String, String> payloadMap = LinkedHashMultimap.create();
        payloadMap.put("Action", "DescribeRegions");

        HttpRequest httpRequest = HttpRequest.builder()
                .endpoint(URI.create("https://ec2.us-east-1.amazonaws.com"))
                .method("POST")
                .headers(headers)
                .payload(new UrlEncodedFormPayload(payloadMap))
                .filter(formSigner)
                .build();
        
        formSigner.filter(httpRequest);
            
        String payload = new String(ByteStreams.toByteArray(httpRequest.getPayload().openStream()));
        System.out.println(httpRequest+"; payload="+payload);

        System.out.println("FormSigner: "+formSigner+"; class="+formSigner.getClass());
        for (Field f : formSigner.getClass().getDeclaredFields()) {
            f.setAccessible(true);
            Object object = f.get(formSigner);
            System.out.println("\t"+f.getName()+" = "+object);
        }
    }
}
