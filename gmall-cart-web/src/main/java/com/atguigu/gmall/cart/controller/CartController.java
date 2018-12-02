package com.atguigu.gmall.cart.controller;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.fastjson.JSON;
import com.atguigu.gmall.annotation.LoginRequire;
import com.atguigu.gmall.bean.CartInfo;
import com.atguigu.gmall.bean.SkuInfo;
import com.atguigu.gmall.service.CartService;
import com.atguigu.gmall.service.SkuService;
import com.atguigu.gmall.util.CookieUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
public class CartController {

    @Reference
    SkuService skuService;
    @Reference
    CartService cartservice;


    @LoginRequire(needSuccess = false)
    @RequestMapping("checkCart")
    public String checkCart(CartInfo cartInfo,HttpServletRequest request,HttpServletResponse response,ModelMap map){

        List<CartInfo> cartInfos = new ArrayList<>();
        String skuId = cartInfo.getSkuId();
        String userId = (String)request.getAttribute("userId");// "2";

        // 修改购物车的勾选状态
        if(StringUtils.isNotBlank(userId)){
            // 修改db
            cartInfo.setUserId(userId);
            cartservice.updateCartByUserId(cartInfo);
            cartInfos = cartservice.getCartsFromCacheByUserId(userId);
        }else{
            // 修改cookie
            String listCartCookie = CookieUtil.getCookieValue(request, "listCartCookie", true);
            cartInfos = JSON.parseArray(listCartCookie, CartInfo.class);
            for (CartInfo info : cartInfos) {
                String skuId1 = info.getSkuId();
                if(skuId1.equals(skuId)){
                    info.setIsChecked(cartInfo.getIsChecked());
                }
            }
            // 覆盖浏览器
            CookieUtil.setCookie(request,response,"listCartCookie",JSON.toJSONString(cartInfos),1000*60*60*24,true);
        }

        // 返回购物车列表的最新数据
        map.put("cartList",cartInfos);
        BigDecimal totalPrice = getTotalPrice(cartInfos);
        map.put("totalPrice",totalPrice);
        return "cartListInner";
    }

    @LoginRequire(needSuccess = false)
    @RequestMapping("cartList")
    public String cartList(HttpServletRequest request,ModelMap map){

        // 声明一个处理后的购物车集合对象
        List<CartInfo> cartInfos = new ArrayList<>();
        String userId = (String)request.getAttribute("userId");// "2";
        // 取出购物车集合
        if(StringUtils.isBlank(userId)){
            // 从cookie取
            String cookieValue = CookieUtil.getCookieValue(request, "listCartCookie", true);
            if(StringUtils.isNotBlank(cookieValue)){
                cartInfos = JSON.parseArray(cookieValue,CartInfo.class);
            }

        }else{
            // 从redis取
            cartInfos = cartservice.getCartsFromCacheByUserId(userId);
        }

        map.put("cartList",cartInfos);
        BigDecimal totalPrice = getTotalPrice(cartInfos);
        map.put("totalPrice",totalPrice);
        return "cartList";
    }

    /***
     * 计算购物车的总价格
     * @param cartInfos
     * @return
     */
    private BigDecimal getTotalPrice(List<CartInfo> cartInfos) {

        BigDecimal totalPrice = new BigDecimal("0");

        for (CartInfo cartInfo : cartInfos) {
            String isChecked = cartInfo.getIsChecked();

            if(isChecked.equals("1")){
                totalPrice = totalPrice.add(cartInfo.getCartPrice());
            }
        }

        return totalPrice;
    }

    @LoginRequire(needSuccess = false)
    @RequestMapping("addToCart")
    public String addToCart(HttpServletRequest request, HttpServletResponse response, @RequestParam Map<String,String> map){


        // 声明一个处理后的购物车集合对象
        List<CartInfo> cartInfos = new ArrayList<>();

        String skuId = map.get("skuId");
        Integer skuNum = Integer.parseInt(map.get("num"));
        SkuInfo skuInfo = skuService.getSkuById(skuId);

        // 封装购物车对象
        CartInfo cartInfo = new CartInfo();
        cartInfo.setCartPrice(skuInfo.getPrice().multiply(new BigDecimal(skuNum)));
        cartInfo.setSkuNum(skuNum);
        cartInfo.setIsChecked("1");
        cartInfo.setSkuId(skuId);
        cartInfo.setSkuName(skuInfo.getSkuName());
        cartInfo.setSkuPrice(skuInfo.getPrice());
        cartInfo.setImgUrl(skuInfo.getSkuDefaultImg());

        // 判断用户是否登陆
        String userId = (String)request.getAttribute("userId");// "2";

        // 添加购物车的业务逻辑
        if(StringUtils.isBlank(userId)){
            // cookie没有用户id
            cartInfo.setUserId("");
            // cookie

            String cookieValue = CookieUtil.getCookieValue(request, "listCartCookie", true);
            if(StringUtils.isBlank(cookieValue)){
                // cookie没有数据
                cartInfos.add(cartInfo);
            }else{
                cartInfos = JSON.parseArray(cookieValue,CartInfo.class);
                // 判断是更新还是添加购物车
                boolean b = if_new_cart(cartInfos,cartInfo);

                if(b){
                    // 新增
                    cartInfos.add(cartInfo);
                }else{
                    // 更新
                    for (CartInfo info : cartInfos) {
                        if(info.getSkuId().equals(cartInfo.getSkuId())){
                            info.setSkuNum(info.getSkuNum()+cartInfo.getSkuNum());
                            info.setCartPrice(info.getSkuPrice().multiply(new BigDecimal(info.getSkuNum())));
                        }
                    }
                }
            }

            // 将购物车数据放入cookie
            CookieUtil.setCookie(request,response,"listCartCookie",JSON.toJSONString(cartInfos),1000*60*60*24,true);


        }else{
            // db有用户id
            cartInfo.setUserId(userId);
            // db
            CartInfo cartInfoDb = cartservice.ifCartExit(cartInfo);

            if(null!=cartInfoDb){
                // 更新
                cartInfoDb.setSkuNum(cartInfoDb.getSkuNum()+cartInfo.getSkuNum());
                cartInfoDb.setCartPrice(cartInfoDb.getSkuPrice().multiply(new BigDecimal(cartInfoDb.getSkuNum())));
                cartservice.updateCart(cartInfoDb);
            }else{
                // 添加
                cartservice.insertCart(cartInfo);
            }

            // 同步缓存
            cartservice.flushCartCacheByUserId(userId);
        }

        return "redirect:/cartSuccess";
    }

    /***
     * 判断购物车数据更新还是新增
     * @param listCartCookie
     * @param cartInfo
     * @return
     */
    private boolean if_new_cart(List<CartInfo> listCartCookie, CartInfo cartInfo) {

        boolean b = true;

        for (CartInfo info : listCartCookie) {
            if(info.getSkuId().equals(cartInfo.getSkuId())){
                b = false;
            }
        }

        return b;
    }

    @LoginRequire(needSuccess = false)
    @RequestMapping("cartSuccess")
    public String cartSuccess(){
        // 添加购物车成功后的重定向页面

        return "success";
    }
}
