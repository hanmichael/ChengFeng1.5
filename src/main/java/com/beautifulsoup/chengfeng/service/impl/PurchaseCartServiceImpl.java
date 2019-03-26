package com.beautifulsoup.chengfeng.service.impl;

import com.beautifulsoup.chengfeng.constant.ChengfengConstant;
import com.beautifulsoup.chengfeng.constant.RedisConstant;
import com.beautifulsoup.chengfeng.controller.vo.PurchaseCartVo;
import com.beautifulsoup.chengfeng.dao.PurchaseProductMapper;
import com.beautifulsoup.chengfeng.dao.PurchaseProductSkuMapper;
import com.beautifulsoup.chengfeng.dao.UserMapper;
import com.beautifulsoup.chengfeng.exception.ParamException;
import com.beautifulsoup.chengfeng.pojo.PurchaseProduct;
import com.beautifulsoup.chengfeng.pojo.PurchaseProductSku;
import com.beautifulsoup.chengfeng.pojo.User;
import com.beautifulsoup.chengfeng.service.PurchaseCartService;
import com.beautifulsoup.chengfeng.service.dto.PurchaseCartItemDto;
import com.beautifulsoup.chengfeng.utils.AuthenticationInfoUtil;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.exception.MemcachedException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static com.beautifulsoup.chengfeng.constant.ChengfengConstant.RabbitMQ.MESSAGE_STOCK_UPDATE;

@Service
public class PurchaseCartServiceImpl implements PurchaseCartService {


    @Autowired
    private PurchaseProductSkuMapper productSkuMapper;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisTemplate<String, Serializable> redisTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private MemcachedClient memcachedClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Override
    public PurchaseCartVo addNewPurchaseProduct(Integer skuId, Integer count) {
        try {
            Boolean checkProduct = stringRedisTemplate.opsForHash().hasKey(RedisConstant.PRODUCT_STOCKS,
                    RedisConstant.PRODUCT_PREFIX_SKU + skuId);
            if (!checkProduct){
                throw new ParamException("商品不存在");
            }
            Integer stock = Integer.parseInt(stringRedisTemplate.opsForHash().get(RedisConstant.PRODUCT_STOCKS,
                    RedisConstant.PRODUCT_PREFIX_SKU + skuId).toString());
            if (stock<count){
                throw new ParamException("商品库存不足");
            }
            User user = AuthenticationInfoUtil.getUser(userMapper, memcachedClient);
            boolean hasProduct = redisTemplate.opsForHash().hasKey(RedisConstant.CART_BELONG_TO + user.getId(),
                    RedisConstant.CART_PRODUCT_PREFIX + skuId).booleanValue();

            if (hasProduct){
                PurchaseCartItemDto cartItemDto = (PurchaseCartItemDto) redisTemplate.opsForHash().get(
                        RedisConstant.CART_BELONG_TO + user.getId(), RedisConstant.CART_PRODUCT_PREFIX + skuId);
                cartItemDto.setCount(count+cartItemDto.getCount());
                cartItemDto.setTotalPrice(cartItemDto.getPrice()*cartItemDto.getCount());

                redisTemplate.opsForHash().put(RedisConstant.CART_BELONG_TO+user.getId(),
                        RedisConstant.CART_PRODUCT_PREFIX+skuId,cartItemDto);
            }else{
                PurchaseProductSku productSku = productSkuMapper.selectAllByPrimaryKey(skuId);
                PurchaseCartItemDto cartItemDto=new PurchaseCartItemDto();
                cartItemDto.setProductId(productSku.getProductId());
                cartItemDto.setSkuId(productSku.getId());
                cartItemDto.setName(productSku.getPurchaseProduct().getName()+" "+productSku.getAttributeName());
                cartItemDto.setCount(count);
                cartItemDto.setSubtitle(productSku.getPurchaseProduct().getSubtitle());
                cartItemDto.setIsChecked(1);
                if (!StringUtils.isBlank(productSku.getPurchaseProduct().getSubImages())){
                    List<String> subImages = Splitter.on(",").splitToList(productSku.getPurchaseProduct().getSubImages());
                    cartItemDto.setSubImage(subImages.get(0));
                }
                cartItemDto.setPrice(productSku.getPrice().doubleValue());
                cartItemDto.setTotalPrice(cartItemDto.getPrice()*cartItemDto.getCount());
                redisTemplate.opsForHash().put(RedisConstant.CART_BELONG_TO+user.getId(),
                        RedisConstant.CART_PRODUCT_PREFIX+skuId,cartItemDto);
            }
            //减库存
            stringRedisTemplate.opsForHash().put(RedisConstant.PRODUCT_STOCKS,
                    RedisConstant.PRODUCT_PREFIX_SKU + skuId,String.valueOf(stock-count));

            rabbitTemplate.convertAndSend(ChengfengConstant.RabbitMQ.TOPIC_EXCHANGE,
                    "topic.stock",skuId+","+(stock-count)+","+count);


            return listAllCartProducts();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (MemcachedException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public PurchaseCartVo listAllCartProducts() {
        try {
            User user = AuthenticationInfoUtil.getUser(userMapper, memcachedClient);
            PurchaseCartVo purchaseCartVo=new PurchaseCartVo();
            if (CollectionUtils.isEmpty(purchaseCartVo.getCartItems())){
                purchaseCartVo.setCartItems(Lists.newArrayList());
            }
            purchaseCartVo.setNickname(user.getNickname());
            redisTemplate.opsForHash().keys(RedisConstant.CART_BELONG_TO+user.getId()).stream().forEach(key->{
                PurchaseCartItemDto cartItem = (PurchaseCartItemDto) redisTemplate.opsForHash().get(
                        RedisConstant.CART_BELONG_TO + user.getId(), key);
                purchaseCartVo.getCartItems().add(cartItem);
            });
            return purchaseCartVo;
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (MemcachedException e) {
            e.printStackTrace();
        } catch (TimeoutException e) {
            e.printStackTrace();
        }
        return null;
    }


}
//rabbitTemplate.convertAndSend(ChengfengConstant.RabbitMQ.STOCK_DELAY_EXCHANGE, "spell_order_delay_queue", "", new MessagePostProcessor() {
//@Override
//public Message postProcessMessage(Message message) throws AmqpException {
//        message.getMessageProperties().setHeader("x-delay",3000);
//        return message;
//        }
//        });