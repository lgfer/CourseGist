package com.coursegist.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.coursegist.server.entity.User;
import com.coursegist.server.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class UserController {

    @Autowired(required = false)
    private UserMapper userMapper;

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            System.out.println("[user] 收到注册请求: " + user.getUsername());
            if (userMapper == null) {
                throw new RuntimeException("UserMapper 未注入");
            }

            QueryWrapper<User> query = new QueryWrapper<>();
            query.eq("username", user.getUsername());
            if (userMapper.selectCount(query) > 0) {
                result.put("code", 400);
                result.put("msg", "该账号已存在");
                return result;
            }

            // 补全默认昵称与角色
            if (user.getNickname() == null || user.getNickname().isEmpty()) {
                user.setNickname("用户" + System.currentTimeMillis());
            }
            user.setRole("USER");

            userMapper.insert(user);

            result.put("code", 200);
            result.put("msg", "注册成功");
            result.put("data", user);
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "后端报错: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody User loginUser) {
        Map<String, Object> result = new HashMap<>();
        try {
            System.out.println("[user] 收到登录请求: " + loginUser.getUsername());

            QueryWrapper<User> query = new QueryWrapper<>();
            query.eq("username", loginUser.getUsername());
            query.eq("password", loginUser.getPassword());

            User dbUser = userMapper.selectOne(query);

            if (dbUser == null) {
                result.put("code", 401);
                result.put("msg", "账号或密码错误");
            } else {
                result.put("code", 200);
                result.put("msg", "登录成功");
                result.put("token", "user_" + dbUser.getId());
                result.put("userInfo", dbUser);
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "登录报错: " + e.getMessage());
        }
        return result;
    }
}