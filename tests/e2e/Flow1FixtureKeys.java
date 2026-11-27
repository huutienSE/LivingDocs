import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.security.crypto.bcrypt.BCrypt;

/** Isolated test credentials only. No production key/registry is changed. */
public class Flow1FixtureKeys {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]); Files.createDirectories(root);
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair pair=generator.generateKeyPair();
        pem(root.resolve("s01-private.pem"),"PRIVATE KEY",pair.getPrivate().getEncoded());
        pem(root.resolve("s01-public.pem"),"PUBLIC KEY",pair.getPublic().getEncoded());
        byte[] pepper=new byte[32]; new SecureRandom().nextBytes(pepper); Files.write(root.resolve("pepper"),pepper);
        List<String> entries=new ArrayList<>();
        for(String workload:List.of("s03","s04")) {
            String secret=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
            Files.writeString(root.resolve(workload+".secret"),secret);
            String operation=workload.equals("s03") ? "source:resolve" : "source:validate";
            String operations=workload.equals("s04") ? "\"source:validate\",\"authorization:evaluate\"" : "\"source:resolve\"";
            String audiences=workload.equals("s04") ? "\"livingdocs-s02-internal\",\"livingdocs-s01-internal\"" : "\"livingdocs-s02-internal\"";
            entries.add("{\"id\":\""+workload+"\",\"secretHash\":\""+BCrypt.hashpw(secret,BCrypt.gensalt())
                    +"\",\"audiences\":["+audiences+"],\"operations\":["+operations+"],\"tenantIds\":[\""+args[1]+"\"]}");
        }
        Files.writeString(root.resolve("workloads.json"),"["+String.join(",",entries)+"]");
    }
    private static void pem(Path file,String label,byte[] bytes) throws Exception {
        Files.writeString(file,"-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)
                +"\n-----END "+label+"-----\n");
    }
}
