public class Main {
    public static void main(String[] args) throws InterruptedException {
        if ("3".equals(args[1])) {
            Thread.sleep(100000L);
        }
        int a = Integer.parseInt(args[0]);
        int b = Integer.parseInt(args[1]);
        System.out.println("结果:" + (a + b));
    }
}