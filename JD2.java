package cn.itcast.spiderJD2;

import cn.itcast.dao.ProductDao;
import cn.itcast.domain.Product;
import cn.itcast.utils.HttpClientUtils;
import com.google.gson.Gson;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.concurrent.*;

public class JD2 {
    //1.在成员方法中定义私有化成员变量,减少后续创建工作
    private static Product product = new Product();
    private static Gson gson;
    private static ProductDao productDao = new ProductDao();
    private static ArrayBlockingQueue<String> blockingDeque = new ArrayBlockingQueue<String>(1000);//设置阻塞队列的最大容量为1000
    private static ExecutorService executorService = Executors.newFixedThreadPool(20);//设置开启的最大线程数目为20个
    public static void main(String[] args) throws Exception {
        //0首先,执行实现出单页的详情信息,而后扩展到多页的数据
        //1.确定请求的url
        String url = "https://search.jd.com/Search?keyword=%E6%89%8B%E6%9C%BA&enc=utf-8&pvid=52827a28093442f5b708c36dd25e407a";
        //2.发送请求,获取数据
        String html = HttpClientUtils.doGet(url);
//        System.out.println(html);
//       //3.解析数据,获取链接
//        parsePid(html);
        //**开启另一个线程,进行监控队列中的元素个数
        executorService.execute(new Runnable() {
            public void run() {
                int i =0;
                //因为是一直处于监控状态,因此,必须采用whlie(true)的形式
                while (true){
                   int size = blockingDeque.size();
                    System.out.println(size);
                    //2.如果线程发现队列中的元素一直处于空状态时,设置一定的等待时间,随时关闭队列
                    if (size==0){
                        i++;
                        if(i==5){
                            //关闭整个线程池,并且跳出循环
                           executorService.shutdown();
                           break;
                        }
                    }
                    //使线程处于阻塞一段时间,以便于有限利用资源
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        //*开启线程,进行解析pid,加快解析速度
        for(int i = 0;i<15;i++){
          executorService.execute(new Runnable() {
              public void run() {
                  while (true){
                      String pid = null;
                      try {
                          //1.获取pid
                          pid = blockingDeque.poll(5000, TimeUnit.MILLISECONDS);
                          //2.判断是否还有pid
                          if(pid==null){
                              break;
                          }
                          //3.解析商品的pid号封装具体的商品详细信息
                          try {
                              product = parseProductPid(pid);
                          } catch (IOException e) {
                              e.printStackTrace();
                          }
                          //4.调用数据层处理信息,存储数据到数据库中!
                          productDao.saveProduct(product);
                      } catch (InterruptedException e) {
                          e.printStackTrace();
                      }
                  }
              }
          });
        }
        //进行分页解析,获取数据
        pageParse();
    }

    private static void pageParse() throws Exception {
        //根据页数和对一个的url解析发现规律page = 2* n -1
        //https://search.jd.com/Search?keyword=%E6%89%8B%E6%9C%BA&enc=utf-8&qrst=1&rt=1&stop=1&vt=2&wq=%E6%89%8B%E6%9C%BA&cid2=653&cid3=655&page=5&s=110&click=0
        for(int i = 1;i<100;i++){
            //1.拼接url
            String pageURL = "https://search.jd.com/Search?keyword=%E6%89%8B%E6%9C%BA&enc=utf-8"+"&page="+(2*i-1);
            //2,发送请求
            String html = HttpClientUtils.doGet(pageURL);
            //3.调用解析具体展示页的信息,
            parsePid(html);
        }

    }

    private static void parsePid(String html) throws Exception {
        //3.1获取document对象
        Document document = Jsoup.parse(html);
        //3.2解析document,获取pid 值得注意的是此处获取的是多个商品的信息,故应该解析出多个商品的pid
        Elements elements = document.select("#J_goodsList ul[class = gl-warp clearfix]>li");
        for (Element element : elements) {
            String pid = element.attr("data-pid");
            System.out.println(pid);

            //将解析得到的pid存放到阻塞队列中
            blockingDeque.put(pid);
//            //4.解析商品的pid号封装具体的商品详细信息
//            product = parseProductPid(pid);
//
//            //5.调用数据层处理信息,存储数据到数据库中!
//            productDao.saveProduct(product);
        }
    }

    private static Product parseProductPid(String pid) throws IOException {
       // 4.拼接url https://item.jd.com/7651951.html?
       String  productURL = "https://item.jd.com/"+pid+".html";
        //5.发送请求,获取数据
        String html = HttpClientUtils.doGet(productURL);
        //6.解析商品的pid获取具体商品的详情信息
        Document document = Jsoup.parse(html);
        //7.实现具体的封装数据
        /**
         *   private String title;
         private String brand;
         private String name;
         private String RAM;
         private String pid;
         private String url;
         private String price;
         */
        //7.1封装
        //3.1 解析商品的标题
        Elements titleTag = document.select("div.sku-name");
        if (titleTag != null && titleTag.size() >0){
            String title = titleTag.text();
            product.setTitle(title);
        }
        //3.2 解析商品的品牌
        Elements brandTag = document.select("#parameter-brand li");
       brandTag = brandTag.get(0).select("a");
        if (brandTag != null && brandTag.size() >0){
            String brand = brandTag.text();
            product.setBrand(brand);
        }
        //3.3 解析商品的名称
        Elements nameTag = document.select("ul[class = parameter2 p-parameter-list] li:first-child");
        if (nameTag != null && nameTag.size()>0){
            String name = nameTag.text();
            product.setName(name);
        }
        //3.4 解析商品的 内存 RAM
        Elements RAMTag = document.select("ul[class = parameter2 p-parameter-list] li[title*=GB]");
        if (RAMTag != null && RAMTag.size()>0){
            String RAM = RAMTag.text();
            product.setName(RAM);
        }

        //https://p.3.cn/prices/mgets?skuIds=J_7357933
        //3.5 获取pid  url
        product.setPid(pid);
        product.setUrl(productURL);
        //3.6 解析商品的价格
        //分析思路,因为通过查看商品的网页可以知道,商品的价格是通过异步加载出来的,同时解析出来的是json字符串
        //https://p.3.cn/prices/mgets?callback=jQuery3809708&type=1&area=1_72_2799_0&skuIds=J_6784504这是访问的网址信息
//        String priceURL = "https://p.3.cn/prices/mgets?skuIds=J_"+pid;
//        String priceJson = HttpClientUtils.doGet(priceURL);
//        System.out.println(priceJson);
//        //gson: 是 谷歌提供的一个解析json的工具包
//        // json中: 两种格式  []  {}
//        //[] : 表示的数组或者是集合
//        // {}: javaBean(对象),或者 map集合
//        //[{"op":"2599.00","m":"9999.00","id":"J_7081550","p":"2599.00"}]
//        List pricelist = gson.fromJson(priceJson, List.class);
//        Map<String,String> maplist = (Map<String,String>) pricelist.get(0);
//        String price = maplist.get("p");
//        product.setPrice(price);
        System.out.println(product);
        return product;
    }
}
