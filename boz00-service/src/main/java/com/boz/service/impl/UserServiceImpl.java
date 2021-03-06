/*
 * Creation : 2016年8月2日
 */
package com.boz.service.impl;

import com.boz.common.constants.Constants;
import com.boz.common.utils.CommonResult;
import com.boz.common.utils.CookieUtils;
import com.boz.common.utils.JsonUtils;
import com.boz.dao.BozUserMapper;
import com.boz.model.BozUser;
import com.boz.model.BozUserExample;
import com.boz.redis.JedisClient;
import com.boz.service.UserService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Named
public class UserServiceImpl implements UserService {

    @Inject
    private BozUserMapper userMapper;

    @Inject
    private JedisClient jedisClient;

    @Value("${SSO_SESSION_EXPIRE}")
    private int SSO_SESSION_EXPIRE;

    @Override
    public CommonResult checkData(String content, Integer type) {
        // 创建查询条件
        BozUserExample example = new BozUserExample();
        BozUserExample.Criteria criteria = example.createCriteria();
        // 对数据进行校验：1、2、3分别代表username、phone、email
        // 用户名校验
        if (1 == type) {
            criteria.andUsernameEqualTo(content);
            // 电话校验
        } else if (2 == type) {
            criteria.andPhoneEqualTo(content);
            // email校验
        } else {
            criteria.andEmailEqualTo(content);
        }
        // 执行查询
        List<BozUser> userList = userMapper.selectByExample(example);
        if (userList == null || CollectionUtils.isEmpty(userList)) {
            return CommonResult.ok(true);
        }
        return CommonResult.ok(false);
    }

    @Override
    public CommonResult createUser(BozUser user) {
        user.setUpdated(new Date());
        user.setCreated(new Date());
        // md5加密
        user.setPassword(DigestUtils.md5DigestAsHex(user.getPassword().getBytes()));
        userMapper.insert(user);
        return CommonResult.ok();
    }

    @Override
    public CommonResult userLogin(String username, String password, HttpServletRequest request, HttpServletResponse response) {
        BozUserExample example = new BozUserExample();
        BozUserExample.Criteria criteria = example.createCriteria();
        criteria.andUsernameEqualTo(username);
        List<BozUser> userList = userMapper.selectByExample(example);
        // 如果没有此用户名
        if (null == userList || CollectionUtils.isEmpty(userList)) {
            return CommonResult.build(400, Constants.ERROR_MSG_USERNAMEORPASSWORDERROR);
        }
        BozUser user = userList.get(0);
        // 比对密码
        if (!DigestUtils.md5DigestAsHex(password.getBytes()).equals(user.getPassword())) {
            return CommonResult.build(400, Constants.ERROR_MSG_USERNAMEORPASSWORDERROR);
        }
        // 判断用户是否已经是登录状态。
        String token = jedisClient.get(user.getUsername());
        if (StringUtils.isNotBlank(token)) {
            jedisClient.set(Constants.REDIS_USER_SESSION_KEY + ":" + token, Constants.FORCED_EXIT);
            jedisClient.expire(Constants.REDIS_USER_SESSION_KEY + ":" + token, SSO_SESSION_EXPIRE);
        }
        // 生成token
        token = UUID.randomUUID().toString();
        // 保存用户之前，把用户对象中的密码清空。
        user.setPassword(null);
        // 把用户信息写入redis
        jedisClient.set(Constants.REDIS_USER_SESSION_KEY + ":" + token, JsonUtils.objectToJson(user));
        // 用户异地登录判断依据
        jedisClient.set(user.getUsername(), token);
        // 设置session的过期时间
        jedisClient.expire(Constants.REDIS_USER_SESSION_KEY + ":" + token, SSO_SESSION_EXPIRE);
        jedisClient.expire(user.getUsername(), SSO_SESSION_EXPIRE);

        // 添加cookie的逻辑，cookie的有效期是关闭浏览器失效
        CookieUtils.setCookie(request, response, Constants.COOKIE_NAME, token);
        // 返回token
        return CommonResult.ok(token);
    }

    @Override
    public CommonResult getUserByToken(String token) {
        // 根据token从redis中查询用户信息
        String json = jedisClient.get(Constants.REDIS_USER_SESSION_KEY + ":" + token);
        // 判断是否为空
        if (StringUtils.isBlank(json)) {
            return CommonResult.build(400, Constants.ERROR_MSG_SESSIONTIMEOUT);
        } else if (Constants.FORCED_EXIT.equals(json)) {
            jedisClient.expire(Constants.REDIS_USER_SESSION_KEY + ":" + token, 0);
            return CommonResult.build(400, Constants.ERROR_MSG_ACCOUNTALREADYLOGIN);
        }
        BozUser user = JsonUtils.jsonToPojo(json, BozUser.class);
        // 更新过期时间
        jedisClient.expire(Constants.REDIS_USER_SESSION_KEY + ":" + token, SSO_SESSION_EXPIRE);
        jedisClient.expire(user.getUsername(), SSO_SESSION_EXPIRE);
        // 返回用户信息
        return CommonResult.ok(user);
    }

    @Override
    public CommonResult userLogout(String token, HttpServletRequest request, HttpServletResponse response) {
        // 根据token从redis中查询用户信息
        String json = jedisClient.get(Constants.REDIS_USER_SESSION_KEY + ":" + token);
        // 不为空表示用户出于登录状态
        if (StringUtils.isNotBlank(json)) {
            BozUser user = JsonUtils.jsonToPojo(json, BozUser.class);
            // 清楚redis中的用户缓存
            jedisClient.expire(Constants.REDIS_USER_SESSION_KEY + ":" + token, 0);
            jedisClient.expire(user.getUsername(), 0);
            // 清楚cookie
            CookieUtils.deleteCookie(request, response, Constants.COOKIE_NAME);
        }
        return CommonResult.ok();
    }

}
