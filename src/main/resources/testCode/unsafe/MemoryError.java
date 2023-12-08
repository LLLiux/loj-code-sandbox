import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        if ("3".equals(args[1])) {
            List<byte[]> bytes = new ArrayList<>();
            while (true) {
                bytes.add(new byte[10000]);
            }
        }
        int a = Integer.parseInt(args[0]);
        int b = Integer.parseInt(args[1]);
        System.out.println("结果:" + (a + b));
    }
}