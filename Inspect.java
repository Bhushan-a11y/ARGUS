import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class Inspect {
    public static void main(String[] args) throws Exception {
        URL url = new URL("file:///C:/Users/bhush/.m2/repository/org/springframework/ai/spring-ai-pgvector-store/2.0.0/spring-ai-pgvector-store-2.0.0.jar");
        URLClassLoader loader = new URLClassLoader(new URL[]{url});
        Class<?> clazz = loader.loadClass("org.springframework.ai.vectorstore.pgvector.PgVectorStore");
        System.out.println("Constructors:");
        for (Constructor<?> c : clazz.getConstructors()) {
            System.out.println(c);
        }
        System.out.println("Methods:");
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals("builder") || m.getName().equals("template")) {
                System.out.println(m);
            }
        }
    }
}
