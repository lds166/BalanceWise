# P1 实施计划：工程骨架 + 用户模块 + 基础资料模块（全栈）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 BalanceWise 前后端工程骨架，完成用户模块（注册/登录/JWT/个人中心后端）与基础资料模块（账户、分类）的全部后端 + 前端页面，达到"能注册登录、能管理账户和分类"的可运行状态。

**Architecture:** 前后端分离。后端 Spring Boot 3 单体（controller/service/mapper 分层，MyBatis-Plus，MySQL 8，JWT 无状态鉴权）；前端 Vue 3 + Vite + Element Plus，axios 经 Vite 代理访问后端。测试用 H2（MySQL 兼容模式）内存库，不写库名以外的真实数据库依赖。

**Tech Stack:** Java 17、Spring Boot 3.3.5、MyBatis-Plus 3.5.7、MySQL 8、jjwt 0.12.6、spring-security-crypto（BCrypt）、Lombok、JUnit 5、H2（test）、Vue 3.5、Vite 5、Element Plus 2.9、Pinia 2、vue-router 4、axios 1。

**后续阶段：** P2 记账模块（余额联动/冲销事务）、P3 账单导入、P4 统计报表、P5 收尾（对账/导出/用户中心前端 + 全面测试）。各阶段计划在本阶段验收后另行编写。

## Global Constraints

- Java 包根：`com.balancewise`；Java 版本 17；Spring Boot 3.3.5；MyBatis-Plus 3.5.7（`mybatis-plus-spring-boot3-starter`）。
- 数据库名：`balancewise`（utf8mb4）；表名：`user`、`account`、`category`、`transaction_record`、`import_batch`（结构以规格文档 v1.1 第 5 章为准，不得增减字段）。
- 统一返回格式：`Result<T>{code,message,data}`，`code=0` 成功；业务异常 `code=400`，未登录 `code=401`。
- 余额规则（后续阶段强制执行）：任何流水 = from 账户减、to 账户加；负债账户余额为负；净资产 = SUM(balance)。
- 后端端口 8080，前端端口 5173，前端经 Vite 代理 `/api` → `http://localhost:8080`。
- 所有写操作必须校验数据归属当前登录用户（user_id 隔离）。
- 每个 Task 结束必须 git commit；Windows 环境，命令在 Git Bash 中执行。

---

### Task 1: 后端工程骨架与公共层

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/com/balancewise/BalanceWiseApplication.java`
- Create: `backend/src/main/java/com/balancewise/common/Result.java`
- Create: `backend/src/main/java/com/balancewise/common/BizException.java`
- Create: `backend/src/main/java/com/balancewise/common/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/balancewise/config/MybatisPlusConfig.java`
- Create: `backend/src/main/java/com/balancewise/config/WebConfig.java`

**Interfaces:**
- Produces: `Result.ok()` / `Result.ok(T)` / `Result.fail(int,String)`；`BizException(String)` / `BizException(int,String)`；全局异常处理（`BizException`→`Result.fail`，`MethodArgumentNotValidException`→400）；MyBatis-Plus 扫描 `com.balancewise.**.mapper`；CORS 放行 `http://localhost:5173`。

- [ ] **Step 1: 确认本机环境**

Run: `java -version 2>&1 | head -1 && mvn -v | head -1 && node -v && mysql --version`
Expected: Java 17.x、Maven 3.9.x、Node 18+、MySQL 8.x。缺哪个装哪个。

- [ ] **Step 2: 写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>
  <groupId>com.balancewise</groupId>
  <artifactId>balancewise-backend</artifactId>
  <version>1.0.0</version>
  <properties>
    <java.version>17</java.version>
    <mybatis-plus.version>3.5.7</mybatis-plus.version>
    <jjwt.version>0.12.6</jjwt.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
      <groupId>com.baomidou</groupId>
      <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
      <version>${mybatis-plus.version}</version>
    </dependency>
    <dependency>
      <groupId>com.mysql</groupId>
      <artifactId>mysql-connector-j</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-api</artifactId>
      <version>${jjwt.version}</version>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-impl</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>io.jsonwebtoken</groupId>
      <artifactId>jjwt-jackson</artifactId>
      <version>${jjwt.version}</version>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-crypto</artifactId>
    </dependency>
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.h2database</groupId>
      <artifactId>h2</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <excludes>
            <exclude>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
            </exclude>
          </excludes>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: 写 application.yml**

```yaml
server:
  port: 8080
spring:
  application:
    name: balancewise
  datasource:
    url: jdbc:mysql://localhost:3306/balancewise?useUnicode=true&characterEncoding=utf8mb4&serverTimezone=Asia/Shanghai
    username: root
    password: root
    driver-class-name: com.mysql.cj.jdbc.Driver
  sql:
    init:
      mode: never
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    banner: false
jwt:
  secret: balancewise-dev-secret-key-please-change-in-production-0123456789abcdef
  expire-hours: 72
cors:
  allowed-origins: http://localhost:5173
```

注意：`password: root` 是占位，改成你本机 MySQL 的 root 密码；`jwt.secret` 生产环境必须更换（论文中可写"密钥配置于服务端，避免硬编码进客户端"）。

- [ ] **Step 4: 写主类与公共层**

`BalanceWiseApplication.java`：

```java
package com.balancewise;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BalanceWiseApplication {
    public static void main(String[] args) {
        SpringApplication.run(BalanceWiseApplication.class, args);
    }
}
```

`Result.java`：

```java
package com.balancewise.common;

import lombok.Data;

@Data
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "ok";
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
```

`BizException.java`：

```java
package com.balancewise.common;

public class BizException extends RuntimeException {
    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
```

`GlobalExceptionHandler.java`：

```java
package com.balancewise.common;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return Result.fail(400, msg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleOther(Exception e) {
        e.printStackTrace();
        return Result.fail(500, "系统异常，请稍后重试");
    }
}
```

`MybatisPlusConfig.java`：

```java
package com.balancewise.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.balancewise.**.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

`WebConfig.java`（CORS，Task 3 会补充拦截器注册）：

```java
package com.balancewise.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `cd backend && mvn -q -DskipTests compile`
Expected: 命令静默结束（无 ERROR），`target/classes` 下生成 .class 文件。

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(p1): 后端工程骨架与公共层（Result/异常/MyBatis-Plus/CORS）"
```

---

### Task 2: 数据库初始化（五张表）

**Files:**
- Create: `backend/src/main/resources/schema.sql`
- Create: `backend/src/test/resources/application-test.yml`

**Interfaces:**
- Produces: MySQL 库 `balancewise` 中 5 张表（结构与规格 v1.1 §5.2 一致）；测试 profile `test` 使用 H2 内存库并自动执行 `schema.sql`。

- [ ] **Step 1: 写 schema.sql**

```sql
CREATE TABLE IF NOT EXISTS `user` (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  nickname VARCHAR(32) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS account (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  name VARCHAR(32) NOT NULL,
  type VARCHAR(16) NOT NULL,
  sub_type VARCHAR(32) NOT NULL,
  balance DECIMAL(12,2) NOT NULL DEFAULT 0,
  initial_balance DECIMAL(12,2) NOT NULL DEFAULT 0,
  remark VARCHAR(255) NULL,
  is_archived TINYINT(1) NOT NULL DEFAULT 0,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_account_user ON account(user_id);

CREATE TABLE IF NOT EXISTS category (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  name VARCHAR(32) NOT NULL,
  kind VARCHAR(8) NOT NULL,
  is_system TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_category_user ON category(user_id);

CREATE TABLE IF NOT EXISTS transaction_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  type VARCHAR(8) NOT NULL,
  from_account_id BIGINT NULL,
  to_account_id BIGINT NULL,
  amount DECIMAL(12,2) NOT NULL,
  category_id BIGINT NULL,
  occurred_at DATETIME NOT NULL,
  counterparty VARCHAR(128) NULL,
  item VARCHAR(255) NULL,
  source VARCHAR(10) NOT NULL,
  external_id VARCHAR(64) NULL,
  import_batch_id BIGINT NULL,
  is_deleted TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_txn_user_time ON transaction_record(user_id, occurred_at);
CREATE INDEX idx_txn_user_src_ext ON transaction_record(user_id, source, external_id);

CREATE TABLE IF NOT EXISTS import_batch (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  platform VARCHAR(8) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  record_count INT NOT NULL,
  total_amount DECIMAL(14,2) NOT NULL,
  status VARCHAR(12) NOT NULL,
  imported_at DATETIME NULL,
  rolled_back_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_batch_user ON import_batch(user_id);
```

- [ ] **Step 2: 本机 MySQL 建库建表**

Run:
```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS balancewise DEFAULT CHARSET utf8mb4;"
mysql -u root -p balancewise < backend/src/main/resources/schema.sql
mysql -u root -p balancewise -e "SHOW TABLES;"
```
Expected: 输出 `account`、`category`、`import_batch`、`transaction_record`、`user` 五张表。
（Windows 若 mysql 客户端不在 PATH，可用 MySQL Workbench / IDEA Database 工具执行同一 SQL。）

- [ ] **Step 3: 写测试用 application-test.yml**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:balancewise;MODE=MySQL;NON_KEYWORDS=USER;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  sql:
    init:
      mode: always
      schema-locations: classpath:schema.sql
jwt:
  secret: balancewise-dev-secret-key-please-change-in-production-0123456789abcdef
  expire-hours: 72
```

`NON_KEYWORDS=USER` 让 H2 允许 `user` 作表名；`MODE=MySQL` 保证 `AUTO_INCREMENT`、`TINYINT`、`DATETIME` 语法兼容。

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/schema.sql backend/src/test/resources/application-test.yml
git commit -m "feat(p1): 数据库 schema（5 表）与 H2 测试配置"
```

---

### Task 3: JWT 鉴权基础设施

**Files:**
- Create: `backend/src/main/java/com/balancewise/auth/JwtUtil.java`
- Create: `backend/src/main/java/com/balancewise/auth/AuthInterceptor.java`
- Create: `backend/src/main/java/com/balancewise/auth/CurrentUser.java`
- Create: `backend/src/main/java/com/balancewise/auth/LoginUserArgumentResolver.java`
- Modify: `backend/src/main/java/com/balancewise/config/WebConfig.java`
- Test: `backend/src/test/java/com/balancewise/auth/JwtUtilTest.java`
- Test: `backend/src/test/java/com/balancewise/auth/AuthApiTest.java`（含测试专用 PingController）

**Interfaces:**
- Consumes: Task 1 的 `Result`/`BizException`。
- Produces:
  - `JwtUtil.generate(Long userId): String`；`JwtUtil.parseUserId(String token): Long`（无效返回 null）
  - `AuthInterceptor.ATTR_USER_ID` 常量；拦截 `/api/**`，放行 `/api/auth/**`
  - 注解 `@CurrentUser`：加在 `Long userId` 类型 controller 形参上，解析当前登录用户 id
- [ ] **Step 1: 写失败的 JwtUtilTest**

`JwtUtilTest.java`：

```java
package com.balancewise.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil() {
        return new JwtUtil("balancewise-dev-secret-key-please-change-in-production-0123456789abcdef", 72);
    }

    @Test
    void generateThenParseReturnsUserId() {
        String token = jwtUtil().generate(42L);
        assertEquals(42L, jwtUtil().parseUserId(token));
    }

    @Test
    void parseTamperedTokenReturnsNull() {
        String token = jwtUtil().generate(42L);
        assertNull(jwtUtil().parseUserId(token + "tampered"));
    }

    @Test
    void parseGarbageReturnsNull() {
        assertNull(jwtUtil().parseUserId("not-a-token"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn -q test -Dtest=JwtUtilTest`
Expected: 编译错误 `JwtUtil` 不存在（类找不到）。

- [ ] **Step 3: 实现 JwtUtil**

```java
package com.balancewise.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long ttlMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire-hours}") long expireHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMillis = expireHours * 3600_000L;
    }

    public String generate(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ttlMillis))
                .signWith(key)
                .compact();
    }

    public Long parseUserId(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Long.valueOf(subject);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=JwtUtilTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 5: 实现拦截器与参数解析器**

`AuthInterceptor.java`：

```java
package com.balancewise.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "LOGIN_USER_ID";

    private final JwtUtil jwtUtil;

    public AuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String header = request.getHeader("Authorization");
        Long userId = null;
        if (header != null && header.startsWith("Bearer ")) {
            userId = jwtUtil.parseUserId(header.substring(7));
        }
        if (userId == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
            return false;
        }
        request.setAttribute(ATTR_USER_ID, userId);
        return true;
    }
}
```

`CurrentUser.java`：

```java
package com.balancewise.auth;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
```

`LoginUserArgumentResolver.java`：

```java
package com.balancewise.auth;

import com.balancewise.common.BizException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class LoginUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType() == Long.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object userId = webRequest.getAttribute(AuthInterceptor.ATTR_USER_ID, NativeWebRequest.SCOPE_REQUEST);
        if (userId == null) {
            throw new BizException(401, "未登录");
        }
        return userId;
    }
}
```

修改 `WebConfig.java`（整体替换为）：

```java
package com.balancewise.config;

import com.balancewise.auth.AuthInterceptor;
import com.balancewise.auth.LoginUserArgumentResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins}")
    private String allowedOrigins;

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("*")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginUserArgumentResolver());
    }
}
```

- [ ] **Step 6: 写鉴权链路测试（先写测试）**

`AuthApiTest.java`（同文件内含测试用 PingController；测试源码与主源码同包，会被组件扫描到）：

```java
package com.balancewise.auth;

import com.balancewise.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @RestController
    static class PingController {
        @GetMapping("/api/test/ping")
        public Result<Long> ping(@CurrentUser Long userId) {
            return Result.ok(userId);
        }
    }

    @Test
    void noTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().is(401));
    }

    @Test
    void validTokenResolvesCurrentUser() throws Exception {
        String token = jwtUtil.generate(7L);
        MvcResult res = mockMvc.perform(get("/api/test/ping")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        String body = res.getResponse().getContentAsString();
        assertTrue(body.contains("\"code\":0"));
        assertTrue(body.contains("\"data\":7"));
    }

    @Test
    void authEndpointsAreExcludedFromInterceptor() throws Exception {
        // /api/auth/** 无 token 也应到达 controller（此处 404 即可证明未被拦截器拦下）
        mockMvc.perform(get("/api/auth/not-exist"))
                .andExpect(status().is(404));
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -q test -Dtest=AuthApiTest`
Expected: 3 个测试全部通过（第一次运行会下载 H2 依赖并初始化 schema，稍慢）。

- [ ] **Step 8: Commit**

```bash
git add backend
git commit -m "feat(p1): JWT 鉴权（拦截器 + @CurrentUser 参数解析）"
```

---

### Task 4: 用户模块后端（注册/登录/个人中心）

**Files:**
- Create: `backend/src/main/java/com/balancewise/user/entity/User.java`
- Create: `backend/src/main/java/com/balancewise/user/mapper/UserMapper.java`
- Create: `backend/src/main/java/com/balancewise/user/dto/RegisterRequest.java`
- Create: `backend/src/main/java/com/balancewise/user/dto/LoginRequest.java`
- Create: `backend/src/main/java/com/balancewise/user/dto/ChangePasswordRequest.java`
- Create: `backend/src/main/java/com/balancewise/user/dto/ChangeNicknameRequest.java`
- Create: `backend/src/main/java/com/balancewise/user/vo/LoginResponse.java`
- Create: `backend/src/main/java/com/balancewise/user/service/UserService.java`
- Create: `backend/src/main/java/com/balancewise/user/controller/AuthController.java`
- Create: `backend/src/main/java/com/balancewise/user/controller/UserController.java`
- Test: `backend/src/test/java/com/balancewise/user/UserServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `JwtUtil`、`@CurrentUser`；Task 5 的 `AccountService.createDefault(Long userId)`、Task 6 的 `CategoryService.seedForUser(Long userId)`（**先在本 Task 内创建这两个空壳方法**，Task 5/6 再充实实现）。
- Produces:
  - `POST /api/auth/register` body `RegisterRequest` → `Result<Void>`
  - `POST /api/auth/login` body `LoginRequest` → `Result<LoginResponse>`（`{id,username,nickname,token}`）
  - `PUT /api/user/password` body `ChangePasswordRequest{oldPassword,newPassword}` → `Result<Void>`
  - `PUT /api/user/nickname` body `ChangeNicknameRequest{nickname}` → `Result<Void>`

- [ ] **Step 1: 写失败的 UserServiceTest**

```java
package com.balancewise.user;

import com.balancewise.common.BizException;
import com.balancewise.user.dto.LoginRequest;
import com.balancewise.user.dto.RegisterRequest;
import com.balancewise.user.service.UserService;
import com.balancewise.user.vo.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class UserServiceTest {

    @Autowired
    private UserService userService;

    private RegisterRequest regReq(String username, String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setNickname("测试员");
        r.setPassword(password);
        r.setConfirmPassword(password);
        return r;
    }

    @Test
    void registerThenLoginSuccess() {
        userService.register(regReq("alice", "secret123"));
        LoginRequest login = new LoginRequest();
        login.setUsername("alice");
        login.setPassword("secret123");
        LoginResponse res = userService.login(login);
        assertEquals("alice", res.getUsername());
        assertEquals("测试员", res.getNickname());
        assertNotNull(res.getToken());
    }

    @Test
    void duplicateUsernameRejected() {
        userService.register(regReq("bob", "secret123"));
        BizException e = assertThrows(BizException.class,
                () -> userService.register(regReq("bob", "other456")));
        assertEquals("用户名已存在", e.getMessage());
    }

    @Test
    void passwordMismatchRejected() {
        RegisterRequest r = regReq("carol", "secret123");
        r.setConfirmPassword("different");
        BizException e = assertThrows(BizException.class, () -> userService.register(r));
        assertEquals("两次输入的密码不一致", e.getMessage());
    }

    @Test
    void wrongPasswordLoginRejected() {
        userService.register(regReq("dave", "secret123"));
        LoginRequest login = new LoginRequest();
        login.setUsername("dave");
        login.setPassword("wrong-pass");
        BizException e = assertThrows(BizException.class, () -> userService.login(login));
        assertEquals("用户名或密码错误", e.getMessage());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=UserServiceTest`
Expected: 编译错误（User/RegisterRequest/UserService 均不存在）。

- [ ] **Step 3: 实现用户模块**

`User.java`：

```java
package com.balancewise.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`UserMapper.java`：

```java
package com.balancewise.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.balancewise.user.entity.User;

public interface UserMapper extends BaseMapper<User> {
}
```

`RegisterRequest.java`：

```java
package com.balancewise.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(min = 4, max = 32, message = "用户名长度须为 4-32 位")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字、下划线")
    private String username;

    @Size(max = 32)
    private String nickname;

    @NotBlank
    @Size(min = 6, max = 64, message = "密码长度须为 6-64 位")
    private String password;

    @NotBlank(message = "请再次输入密码")
    private String confirmPassword;
}
```

`LoginRequest.java`：

```java
package com.balancewise.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
}
```

`ChangePasswordRequest.java`：

```java
package com.balancewise.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank
    private String oldPassword;

    @NotBlank
    @Size(min = 6, max = 64, message = "密码长度须为 6-64 位")
    private String newPassword;
}
```

`ChangeNicknameRequest.java`：

```java
package com.balancewise.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangeNicknameRequest {
    @NotBlank
    @Size(max = 32)
    private String nickname;
}
```

`LoginResponse.java`：

```java
package com.balancewise.user.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private Long id;
    private String username;
    private String nickname;
    private String token;
}
```

`UserService.java`（依赖的 `AccountService`、`CategoryService` 先建空壳，见 Step 4）：

```java
package com.balancewise.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.account.service.AccountService;
import com.balancewise.auth.JwtUtil;
import com.balancewise.category.service.CategoryService;
import com.balancewise.common.BizException;
import com.balancewise.user.dto.ChangeNicknameRequest;
import com.balancewise.user.dto.ChangePasswordRequest;
import com.balancewise.user.dto.LoginRequest;
import com.balancewise.user.dto.RegisterRequest;
import com.balancewise.user.entity.User;
import com.balancewise.user.mapper.UserMapper;
import com.balancewise.user.vo.LoginResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService extends ServiceImpl<UserMapper, User> {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final JwtUtil jwtUtil;
    private final CategoryService categoryService;
    private final AccountService accountService;

    public UserService(JwtUtil jwtUtil, CategoryService categoryService, AccountService accountService) {
        this.jwtUtil = jwtUtil;
        this.categoryService = categoryService;
        this.accountService = accountService;
    }

    @Transactional
    public void register(RegisterRequest req) {
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new BizException("两次输入的密码不一致");
        }
        long exists = count(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (exists > 0) {
            throw new BizException("用户名已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setNickname(req.getNickname() == null || req.getNickname().isBlank()
                ? req.getUsername() : req.getNickname());
        u.setPasswordHash(encoder.encode(req.getPassword()));
        save(u);
        categoryService.seedForUser(u.getId());
        accountService.createDefault(u.getId());
    }

    public LoginResponse login(LoginRequest req) {
        User u = getOne(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername()));
        if (u == null || !encoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new BizException("用户名或密码错误");
        }
        return new LoginResponse(u.getId(), u.getUsername(), u.getNickname(), jwtUtil.generate(u.getId()));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User u = getById(userId);
        if (u == null || !encoder.matches(req.getOldPassword(), u.getPasswordHash())) {
            throw new BizException("原密码不正确");
        }
        u.setPasswordHash(encoder.encode(req.getNewPassword()));
        updateById(u);
    }

    @Transactional
    public void changeNickname(Long userId, ChangeNicknameRequest req) {
        User u = getById(userId);
        if (u == null) {
            throw new BizException("用户不存在");
        }
        u.setNickname(req.getNickname());
        updateById(u);
    }
}
```

`AuthController.java`：

```java
package com.balancewise.user.controller;

import com.balancewise.common.Result;
import com.balancewise.user.dto.LoginRequest;
import com.balancewise.user.dto.RegisterRequest;
import com.balancewise.user.service.UserService;
import com.balancewise.user.vo.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest req) {
        userService.register(req);
        return Result.ok();
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(userService.login(req));
    }
}
```

`UserController.java`：

```java
package com.balancewise.user.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import com.balancewise.user.dto.ChangeNicknameRequest;
import com.balancewise.user.dto.ChangePasswordRequest;
import com.balancewise.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@CurrentUser Long userId,
                                       @Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(userId, req);
        return Result.ok();
    }

    @PutMapping("/nickname")
    public Result<Void> changeNickname(@CurrentUser Long userId,
                                       @Valid @RequestBody ChangeNicknameRequest req) {
        userService.changeNickname(userId, req);
        return Result.ok();
    }
}
```

- [ ] **Step 4: 建 AccountService / CategoryService 空壳（让编译通过）**

`backend/src/main/java/com/balancewise/account/service/AccountService.java`：

```java
package com.balancewise.account.service;

import org.springframework.stereotype.Service;

@Service
public class AccountService {
    public void createDefault(Long userId) {
        // Task 5 实现：为用户创建默认现金账户
    }
}
```

`backend/src/main/java/com/balancewise/category/service/CategoryService.java`：

```java
package com.balancewise.category.service;

import org.springframework.stereotype.Service;

@Service
public class CategoryService {
    public void seedForUser(Long userId) {
        // Task 6 实现：为用户初始化预置分类
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q test -Dtest=UserServiceTest`
Expected: 4 个测试全部通过。

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(p1): 用户模块后端（注册/登录/改密/改昵称 + 注册初始化钩子）"
```

---

### Task 5: 账户模块后端（CRUD / 归档 / 净资产汇总 / 删除保护）

**Files:**
- Create: `backend/src/main/java/com/balancewise/account/entity/Account.java`
- Create: `backend/src/main/java/com/balancewise/account/mapper/AccountMapper.java`
- Create: `backend/src/main/java/com/balancewise/account/dto/AccountRequest.java`
- Create: `backend/src/main/java/com/balancewise/account/dto/ArchiveRequest.java`
- Create: `backend/src/main/java/com/balancewise/account/vo/AccountSummaryVo.java`
- Create: `backend/src/main/java/com/balancewise/account/service/AccountService.java`（覆盖 Task 4 空壳）
- Create: `backend/src/main/java/com/balancewise/account/controller/AccountController.java`
- Create: `backend/src/main/java/com/balancewise/record/entity/TransactionRecord.java`（P2 使用，本 Task 仅建实体与计数查询）
- Create: `backend/src/main/java/com/balancewise/record/mapper/TransactionRecordMapper.java`
- Test: `backend/src/test/java/com/balancewise/account/AccountServiceTest.java`

**Interfaces:**
- Consumes: Task 3 `@CurrentUser`；Task 2 schema。
- Produces:
  - `AccountService.createDefault(Long userId)`：创建"现金"账户（余额 0）
  - `AccountService.countByAccount(Long userId, Long accountId): long`：该账户作为 from/to 的未删除流水数（P2 记账仍会用）
  - `GET /api/accounts` → `Result<AccountSummaryVo>`（`{accounts, netWorth, assetTotal, liabilityTotal}`，BigDecimal 均为两位小数）
  - `POST /api/accounts`、`PUT /api/accounts/{id}`、`DELETE /api/accounts/{id}`、`PUT /api/accounts/{id}/archive`
  - `TransactionRecord` 实体（字段与规格 §5.2 一致，P2 直接复用）

- [ ] **Step 1: 写失败的 AccountServiceTest**

```java
package com.balancewise.account;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.account.vo.AccountSummaryVo;
import com.balancewise.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class AccountServiceTest {

    @Autowired
    private AccountService accountService;

    private AccountRequest req(String name, String type, String subType, String balance) {
        AccountRequest r = new AccountRequest();
        r.setName(name);
        r.setType(type);
        r.setSubType(subType);
        r.setInitialBalance(new BigDecimal(balance));
        r.setRemark("");
        return r;
    }

    @Test
    void assetAccountBalancePositive() {
        Account a = accountService.create(1L, req("招商卡", "asset", "debit_card", "100.00"));
        assertEquals(0, new BigDecimal("100.00").compareTo(a.getBalance()));
    }

    @Test
    void liabilityAccountBalanceNegative() {
        // 负债账户：欠款以负数存储
        Account a = accountService.create(1L, req("信用卡", "liability", "credit_card", "5000.00"));
        assertEquals(0, new BigDecimal("-5000.00").compareTo(a.getBalance()));
    }

    @Test
    void subTypeMustMatchType() {
        BizException e = assertThrows(BizException.class,
                () -> accountService.create(1L, req("错卡", "asset", "credit_card", "1.00")));
        assertEquals("子类型与账户类别不匹配", e.getMessage());
    }

    @Test
    void cannotOperateOtherUsersAccount() {
        Account mine = accountService.create(1L, req("我的卡", "asset", "cash", "0.00"));
        BizException e = assertThrows(BizException.class,
                () -> accountService.archive(2L, mine.getId(), true));
        assertEquals("账户不存在", e.getMessage());
    }

    @Test
    void summaryComputesNetWorth() {
        accountService.create(1L, req("资产A", "asset", "cash", "1000.00"));
        accountService.create(1L, req("负债B", "liability", "huabei", "300.00"));
        AccountSummaryVo s = accountService.summary(1L);
        assertEquals(0, new BigDecimal("700.00").compareTo(s.getNetWorth()));
        assertEquals(0, new BigDecimal("1000.00").compareTo(s.getAssetTotal()));
        assertEquals(0, new BigDecimal("300.00").compareTo(s.getLiabilityTotal()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=AccountServiceTest`
Expected: 编译错误（Account/AccountRequest/AccountService 方法不存在）。

- [ ] **Step 3: 实现账户模块**

`TransactionRecord.java`（P2 记账的核心实体，本 Task 先建好供删除保护查询）：

```java
package com.balancewise.record.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("transaction_record")
public class TransactionRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String type;
    private Long fromAccountId;
    private Long toAccountId;
    private BigDecimal amount;
    private Long categoryId;
    private LocalDateTime occurredAt;
    private String counterparty;
    private String item;
    private String source;
    private String externalId;
    private Long importBatchId;
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`TransactionRecordMapper.java`：

```java
package com.balancewise.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.balancewise.record.entity.TransactionRecord;

public interface TransactionRecordMapper extends BaseMapper<TransactionRecord> {
}
```

`Account.java`：

```java
package com.balancewise.account.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("account")
public class Account {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String name;
    private String type;
    private String subType;
    private BigDecimal balance;
    private BigDecimal initialBalance;
    private String remark;
    private Integer isArchived;
    private Integer sortOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`AccountMapper.java`：

```java
package com.balancewise.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.balancewise.account.entity.Account;

public interface AccountMapper extends BaseMapper<Account> {
}
```

`AccountRequest.java`：

```java
package com.balancewise.account.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class AccountRequest {
    @NotBlank
    @Size(max = 32)
    private String name;

    @NotBlank
    @Pattern(regexp = "^(asset|liability)$", message = "账户类别只能是 asset 或 liability")
    private String type;

    @NotBlank
    private String subType;

    @NotNull
    @DecimalMin(value = "0.00", message = "初始余额不能为负")
    private BigDecimal initialBalance;

    @Size(max = 255)
    private String remark;
}
```

`ArchiveRequest.java`：

```java
package com.balancewise.account.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ArchiveRequest {
    @NotNull
    private Boolean isArchived;
}
```

`AccountSummaryVo.java`：

```java
package com.balancewise.account.vo;

import com.balancewise.account.entity.Account;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class AccountSummaryVo {
    private List<Account> accounts;
    private BigDecimal netWorth;
    private BigDecimal assetTotal;
    private BigDecimal liabilityTotal;
}
```

`AccountService.java`（整体替换 Task 4 的空壳）：

```java
package com.balancewise.account.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.mapper.AccountMapper;
import com.balancewise.account.vo.AccountSummaryVo;
import com.balancewise.common.BizException;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
public class AccountService extends ServiceImpl<AccountMapper, Account> {

    public static final Set<String> ASSET_SUBTYPES = Set.of("cash", "debit_card", "wechat", "alipay", "yuebao", "custom");
    public static final Set<String> LIABILITY_SUBTYPES = Set.of("credit_card", "huabei", "custom");

    private final TransactionRecordMapper transactionRecordMapper;

    public AccountService(TransactionRecordMapper transactionRecordMapper) {
        this.transactionRecordMapper = transactionRecordMapper;
    }

    @Transactional
    public void createDefault(Long userId) {
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType("asset");
        a.setSubType("cash");
        a.setBalance(BigDecimal.ZERO.setScale(2));
        a.setInitialBalance(BigDecimal.ZERO.setScale(2));
        a.setIsArchived(0);
        a.setSortOrder(0);
        save(a);
    }

    private void validateSubType(String type, String subType) {
        boolean ok = "asset".equals(type) ? ASSET_SUBTYPES.contains(subType)
                : LIABILITY_SUBTYPES.contains(subType);
        if (!ok) {
            throw new BizException("子类型与账户类别不匹配");
        }
    }

    @Transactional
    public Account create(Long userId, AccountRequest req) {
        validateSubType(req.getType(), req.getSubType());
        Account a = new Account();
        a.setUserId(userId);
        a.setName(req.getName());
        a.setType(req.getType());
        a.setSubType(req.getSubType());
        // 负债账户余额为负（欠款）
        BigDecimal initial = req.getInitialBalance().setScale(2);
        a.setInitialBalance(initial);
        a.setBalance("asset".equals(req.getType()) ? initial : initial.negate());
        a.setRemark(req.getRemark());
        a.setIsArchived(0);
        a.setSortOrder(0);
        save(a);
        return a;
    }

    private Account owned(Long userId, Long accountId) {
        Account a = getById(accountId);
        if (a == null || !a.getUserId().equals(userId)) {
            throw new BizException("账户不存在");
        }
        return a;
    }

    @Transactional
    public Account update(Long userId, Long accountId, AccountRequest req) {
        Account a = owned(userId, accountId);
        validateSubType(req.getType(), req.getSubType());
        a.setName(req.getName());
        a.setType(req.getType());
        a.setSubType(req.getSubType());
        a.setRemark(req.getRemark());
        updateById(a);
        return a;
    }

    @Transactional
    public void delete(Long userId, Long accountId) {
        Account a = owned(userId, accountId);
        if (countByAccount(userId, accountId) > 0) {
            throw new BizException("该账户存在交易记录，无法删除，可选择归档");
        }
        removeById(a.getId());
    }

    @Transactional
    public void archive(Long userId, Long accountId, boolean isArchived) {
        Account a = owned(userId, accountId);
        a.setIsArchived(isArchived ? 1 : 0);
        updateById(a);
    }

    public long countByAccount(Long userId, Long accountId) {
        return transactionRecordMapper.selectCount(new LambdaQueryWrapper<TransactionRecord>()
                .eq(TransactionRecord::getUserId, userId)
                .eq(TransactionRecord::getIsDeleted, 0)
                .and(w -> w.eq(TransactionRecord::getFromAccountId, accountId)
                        .or().eq(TransactionRecord::getToAccountId, accountId)));
    }

    public AccountSummaryVo summary(Long userId) {
        List<Account> accounts = list(new LambdaQueryWrapper<Account>()
                .eq(Account::getUserId, userId)
                .orderByAsc(Account::getSortOrder)
                .orderByAsc(Account::getId));
        BigDecimal netWorth = BigDecimal.ZERO;
        BigDecimal assetTotal = BigDecimal.ZERO;
        BigDecimal liabilityTotal = BigDecimal.ZERO;
        for (Account a : accounts) {
            netWorth = netWorth.add(a.getBalance());
            if ("asset".equals(a.getType())) {
                assetTotal = assetTotal.add(a.getBalance());
            } else {
                liabilityTotal = liabilityTotal.add(a.getBalance().negate());
            }
        }
        AccountSummaryVo vo = new AccountSummaryVo();
        vo.setAccounts(accounts);
        vo.setNetWorth(netWorth);
        vo.setAssetTotal(assetTotal);
        vo.setLiabilityTotal(liabilityTotal);
        return vo;
    }
}
```

`AccountController.java`：

```java
package com.balancewise.account.controller;

import com.balancewise.account.dto.AccountRequest;
import com.balancewise.account.dto.ArchiveRequest;
import com.balancewise.account.entity.Account;
import com.balancewise.account.service.AccountService;
import com.balancewise.account.vo.AccountSummaryVo;
import com.balancewise.auth.CurrentUser;
import com.balancewise.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public Result<AccountSummaryVo> list(@CurrentUser Long userId) {
        return Result.ok(accountService.summary(userId));
    }

    @PostMapping
    public Result<Account> create(@CurrentUser Long userId, @Valid @RequestBody AccountRequest req) {
        return Result.ok(accountService.create(userId, req));
    }

    @PutMapping("/{id}")
    public Result<Account> update(@CurrentUser Long userId, @PathVariable Long id,
                                  @Valid @RequestBody AccountRequest req) {
        return Result.ok(accountService.update(userId, id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        accountService.delete(userId, id);
        return Result.ok();
    }

    @PutMapping("/{id}/archive")
    public Result<Void> archive(@CurrentUser Long userId, @PathVariable Long id,
                                @Valid @RequestBody ArchiveRequest req) {
        accountService.archive(userId, id, req.getIsArchived());
        return Result.ok();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=AccountServiceTest`
Expected: 5 个测试全部通过。

- [ ] **Step 5: Commit**

```bash
git add backend
git commit -m "feat(p1): 账户模块后端（CRUD/归档/净资产汇总/删除保护/负债负余额）"
```

---

### Task 6: 分类模块后端（预置种子 + 两级树 + 增删改规则）

**Files:**
- Create: `backend/src/main/java/com/balancewise/category/entity/Category.java`
- Create: `backend/src/main/java/com/balancewise/category/mapper/CategoryMapper.java`
- Create: `backend/src/main/java/com/balancewise/category/dto/CategoryRequest.java`
- Create: `backend/src/main/java/com/balancewise/category/dto/RenameCategoryRequest.java`
- Create: `backend/src/main/java/com/balancewise/category/vo/CategoryVo.java`
- Create: `backend/src/main/java/com/balancewise/category/service/CategoryService.java`（覆盖 Task 4 空壳）
- Create: `backend/src/main/java/com/balancewise/category/controller/CategoryController.java`
- Test: `backend/src/test/java/com/balancewise/category/CategoryServiceTest.java`

**Interfaces:**
- Consumes: Task 3 `@CurrentUser`；Task 5 `TransactionRecordMapper`。
- Produces:
  - `CategoryService.seedForUser(Long userId)`：写入 9 个支出一级 + 22 个子类、5 个收入一级（`is_system = 1`）
  - `GET /api/categories?kind=expense|income` → `Result<List<CategoryVo>>`（两级树，`CategoryVo{id,name,kind,isSystem,children}`)
  - `POST /api/categories` body `{name,kind,parentId?}`、`PUT /api/categories/{id}` body `{name}`、`DELETE /api/categories/{id}`

- [ ] **Step 1: 写失败的 CategoryServiceTest**

```java
package com.balancewise.category;

import com.balancewise.category.dto.CategoryRequest;
import com.balancewise.category.entity.Category;
import com.balancewise.category.service.CategoryService;
import com.balancewise.category.vo.CategoryVo;
import com.balancewise.common.BizException;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CategoryServiceTest {

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private TransactionRecordMapper transactionRecordMapper;

    @Test
    void seedCreatesDefaultsForNewUser() {
        categoryService.seedForUser(100L);
        List<CategoryVo> expense = categoryService.tree(100L, "expense");
        List<CategoryVo> income = categoryService.tree(100L, "income");
        assertEquals(9, expense.size());
        assertEquals(5, income.size());
        assertEquals(4, expense.get(0).getChildren().size()); // 餐饮：三餐/外卖/聚餐/零食饮料
        assertEquals(Integer.valueOf(1), expense.get(0).getIsSystem());
    }

    @Test
    void createRejectsDuplicateSiblingName() {
        categoryService.seedForUser(101L);
        List<CategoryVo> tree = categoryService.tree(101L, "expense");
        CategoryVo dining = tree.get(0); // 餐饮
        CategoryRequest req = new CategoryRequest();
        req.setKind("expense");
        req.setName("外卖");
        req.setParentId(dining.getId());
        BizException e = assertThrows(BizException.class, () -> categoryService.create(101L, req));
        assertEquals("同级下已存在同名分类", e.getMessage());
    }

    @Test
    void systemCategoryCannotBeDeleted() {
        categoryService.seedForUser(102L);
        CategoryVo first = categoryService.tree(102L, "expense").get(0);
        BizException e = assertThrows(BizException.class, () -> categoryService.delete(102L, first.getId()));
        assertEquals("预置分类不可删除", e.getMessage());
    }

    @Test
    void categoryReferencedByTransactionCannotBeDeleted() {
        categoryService.seedForUser(103L);
        CategoryRequest req = new CategoryRequest();
        req.setKind("expense");
        req.setName("临时分类");
        Category custom = categoryService.create(103L, req);

        TransactionRecord tx = new TransactionRecord();
        tx.setUserId(103L);
        tx.setType("expense");
        tx.setAmount(new BigDecimal("10.00"));
        tx.setCategoryId(custom.getId());
        tx.setOccurredAt(LocalDateTime.now());
        tx.setSource("manual");
        tx.setIsDeleted(0);
        transactionRecordMapper.insert(tx);

        BizException e = assertThrows(BizException.class, () -> categoryService.delete(103L, custom.getId()));
        assertEquals("该分类已被交易使用，无法删除", e.getMessage());
    }

    @Test
    void renameWorksAndDuplicateRejected() {
        categoryService.seedForUser(104L);
        CategoryVo first = categoryService.tree(104L, "income").get(0); // 工资薪资
        categoryService.rename(104L, first.getId(), "主业工资");
        assertEquals("主业工资", categoryService.tree(104L, "income").get(0).getName());
        CategoryVo second = categoryService.tree(104L, "income").get(1);
        BizException e = assertThrows(BizException.class,
                () -> categoryService.rename(104L, second.getId(), "主业工资"));
        assertEquals("同级下已存在同名分类", e.getMessage());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=CategoryServiceTest`
Expected: 编译错误（Category/CategoryService 方法不存在）。

- [ ] **Step 3: 实现分类模块**

`Category.java`：

```java
package com.balancewise.category.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("category")
public class Category {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long parentId;
    private String name;
    private String kind;
    private Integer isSystem;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

`CategoryMapper.java`：

```java
package com.balancewise.category.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.balancewise.category.entity.Category;

public interface CategoryMapper extends BaseMapper<Category> {
}
```

`CategoryRequest.java`：

```java
package com.balancewise.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryRequest {
    @NotBlank
    @Size(max = 32)
    private String name;

    @NotBlank
    @Pattern(regexp = "^(income|expense)$", message = "kind 只能是 income 或 expense")
    private String kind;

    private Long parentId; // 为空表示一级分类
}
```

`RenameCategoryRequest.java`：

```java
package com.balancewise.category.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RenameCategoryRequest {
    @NotBlank
    @Size(max = 32)
    private String name;
}
```

`CategoryVo.java`：

```java
package com.balancewise.category.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CategoryVo {
    private Long id;
    private String name;
    private String kind;
    private Integer isSystem;
    private List<CategoryVo> children = new ArrayList<>();
}
```

`CategoryService.java`（整体替换 Task 4 的空壳）：

```java
package com.balancewise.category.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.balancewise.category.dto.CategoryRequest;
import com.balancewise.category.entity.Category;
import com.balancewise.category.mapper.CategoryMapper;
import com.balancewise.category.vo.CategoryVo;
import com.balancewise.common.BizException;
import com.balancewise.record.entity.TransactionRecord;
import com.balancewise.record.mapper.TransactionRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CategoryService extends ServiceImpl<CategoryMapper, Category> {

    public static final String[][] EXPENSE_DEFAULTS = {
            {"餐饮", "三餐", "外卖", "聚餐", "零食饮料"},
            {"交通", "公交地铁", "打车", "火车机票", "加油停车"},
            {"购物", "服饰鞋包", "数码家电", "日用百货", "美妆护肤"},
            {"居住", "房租", "水电燃气", "物业宽带"},
            {"娱乐", "电影游戏", "旅游度假", "运动健身", "会员充值"},
            {"医疗", "药品", "门诊住院"},
            {"教育", "书籍资料", "课程培训"},
            {"人情往来", "红包礼金", "请客送礼"},
            {"其他支出"},
    };

    public static final String[][] INCOME_DEFAULTS = {
            {"工资薪资"}, {"兼职副业"}, {"理财收益"}, {"红包礼金"}, {"其他收入"},
    };

    private final TransactionRecordMapper transactionRecordMapper;

    public CategoryService(TransactionRecordMapper transactionRecordMapper) {
        this.transactionRecordMapper = transactionRecordMapper;
    }

    @Transactional
    public void seedForUser(Long userId) {
        insertDefaults(userId, "expense", EXPENSE_DEFAULTS);
        insertDefaults(userId, "income", INCOME_DEFAULTS);
    }

    private void insertDefaults(Long userId, String kind, String[][] defaults) {
        for (String[] group : defaults) {
            Category parent = new Category();
            parent.setUserId(userId);
            parent.setParentId(null);
            parent.setName(group[0]);
            parent.setKind(kind);
            parent.setIsSystem(1);
            save(parent);
            for (int i = 1; i < group.length; i++) {
                Category child = new Category();
                child.setUserId(userId);
                child.setParentId(parent.getId());
                child.setName(group[i]);
                child.setKind(kind);
                child.setIsSystem(1);
                save(child);
            }
        }
    }

    public List<CategoryVo> tree(Long userId, String kind) {
        List<Category> all = list(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, kind)
                .orderByAsc(Category::getId));
        Map<Long, CategoryVo> byId = new LinkedHashMap<>();
        for (Category c : all) {
            CategoryVo vo = new CategoryVo();
            vo.setId(c.getId());
            vo.setName(c.getName());
            vo.setKind(c.getKind());
            vo.setIsSystem(c.getIsSystem());
            byId.put(c.getId(), vo);
        }
        List<CategoryVo> roots = new ArrayList<>();
        for (Category c : all) {
            CategoryVo vo = byId.get(c.getId());
            if (c.getParentId() == null) {
                roots.add(vo);
            } else {
                CategoryVo parent = byId.get(c.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                }
            }
        }
        return roots;
    }

    private void ensureNoDuplicateName(Long userId, String kind, Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<Category> w = new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getKind, kind)
                .eq(Category::getName, name)
                .eq(parentId != null, Category::getParentId, parentId)
                .isNull(parentId == null, Category::getParentId);
        if (excludeId != null) {
            w.ne(Category::getId, excludeId);
        }
        if (count(w) > 0) {
            throw new BizException("同级下已存在同名分类");
        }
    }

    private Category owned(Long userId, Long id) {
        Category c = getById(id);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new BizException("分类不存在");
        }
        return c;
    }

    @Transactional
    public Category create(Long userId, CategoryRequest req) {
        Long parentId = req.getParentId();
        if (parentId != null) {
            Category parent = owned(userId, parentId);
            if (!parent.getKind().equals(req.getKind())) {
                throw new BizException("子分类与父分类类型不一致");
            }
            if (parent.getParentId() != null) {
                throw new BizException("只能在一级分类下新增子分类");
            }
        }
        ensureNoDuplicateName(userId, req.getKind(), parentId, req.getName(), null);
        Category c = new Category();
        c.setUserId(userId);
        c.setParentId(parentId);
        c.setName(req.getName());
        c.setKind(req.getKind());
        c.setIsSystem(0);
        save(c);
        return c;
    }

    @Transactional
    public void rename(Long userId, Long id, String newName) {
        Category c = owned(userId, id);
        ensureNoDuplicateName(userId, c.getKind(), c.getParentId(), newName, id);
        c.setName(newName);
        updateById(c);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        Category c = owned(userId, id);
        if (Integer.valueOf(1).equals(c.getIsSystem())) {
            throw new BizException("预置分类不可删除");
        }
        long children = count(new LambdaQueryWrapper<Category>()
                .eq(Category::getUserId, userId)
                .eq(Category::getParentId, id));
        if (children > 0) {
            throw new BizException("请先删除该分类下的子分类");
        }
        long refs = transactionRecordMapper.selectCount(new LambdaQueryWrapper<TransactionRecord>()
                .eq(TransactionRecord::getUserId, userId)
                .eq(TransactionRecord::getCategoryId, id)
                .eq(TransactionRecord::getIsDeleted, 0));
        if (refs > 0) {
            throw new BizException("该分类已被交易使用，无法删除");
        }
        removeById(id);
    }
}
```

`CategoryController.java`：

```java
package com.balancewise.category.controller;

import com.balancewise.auth.CurrentUser;
import com.balancewise.category.dto.CategoryRequest;
import com.balancewise.category.dto.RenameCategoryRequest;
import com.balancewise.category.entity.Category;
import com.balancewise.category.service.CategoryService;
import com.balancewise.category.vo.CategoryVo;
import com.balancewise.common.Result;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public Result<List<CategoryVo>> tree(@CurrentUser Long userId,
                                         @RequestParam String kind) {
        return Result.ok(categoryService.tree(userId, kind));
    }

    @PostMapping
    public Result<Category> create(@CurrentUser Long userId,
                                   @Valid @RequestBody CategoryRequest req) {
        return Result.ok(categoryService.create(userId, req));
    }

    @PutMapping("/{id}")
    public Result<Void> rename(@CurrentUser Long userId, @PathVariable Long id,
                               @Valid @RequestBody RenameCategoryRequest req) {
        categoryService.rename(userId, id, req.getName());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@CurrentUser Long userId, @PathVariable Long id) {
        categoryService.delete(userId, id);
        return Result.ok();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=CategoryServiceTest`
Expected: 5 个测试全部通过。

- [ ] **Step 5: 全量回归**

Run: `mvn -q test`
Expected: `Tests run` 全部通过，0 failures / 0 errors（JwtUtil 3 + AuthApi 3 + User 4 + Account 5 + Category 5 = 20 个）。

- [ ] **Step 6: Commit**

```bash
git add backend
git commit -m "feat(p1): 分类模块后端（预置种子/两级树/增删改规则）"
```

---

### Task 7: 前端工程骨架（Vite / 路由 / Pinia / axios / 主布局）

**Files:**
- Create: `frontend/package.json`、`frontend/vite.config.js`、`frontend/index.html`
- Create: `frontend/src/main.js`、`frontend/src/App.vue`
- Create: `frontend/src/api/request.js`
- Create: `frontend/src/stores/user.js`
- Create: `frontend/src/router/index.js`
- Create: `frontend/src/layouts/MainLayout.vue`
- Create: `frontend/src/views/PlaceholderView.vue`

**Interfaces:**
- Consumes: Task 4 的 `POST /api/auth/register`、`POST /api/auth/login`。
- Produces:
  - axios 实例 `request`：`request.get/post/put/delete(url[, data])` 返回 `Promise<Result>`（`res.code === 0` 时 resolve `res`，否则 ElMessage 报错并 reject）；自动带 `Authorization: Bearer <token>`；401 自动清 token 跳 `/login`
  - `useUserStore`（Pinia）：`state{token,user}`；`login(form)`、`register(form)`、`logout()`
  - 路由表：登录页 `/login`、注册页 `/register`、主布局 `/`（子路由 `dashboard/record/transactions/import/accounts/categories/stats`，除 accounts/categories 外本阶段为 PlaceholderView）
  - 本地存储键：`bw_token`、`bw_user`

- [ ] **Step 1: 写脚手架文件**

`package.json`：

```json
{
  "name": "balancewise-frontend",
  "private": true,
  "version": "1.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "@element-plus/icons-vue": "^2.3.1",
    "axios": "^1.7.7",
    "echarts": "^5.6.0",
    "element-plus": "^2.9.3",
    "pinia": "^2.2.6",
    "vue": "^3.5.13",
    "vue-router": "^4.4.5"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.2.1",
    "vite": "^5.4.11"
  }
}
```

`vite.config.js`：

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
```

`index.html`：

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>BalanceWise 个人账务管理</title>
</head>
<body>
  <div id="app"></div>
  <script type="module" src="/src/main.js"></script>
</body>
</html>
```

`src/main.js`：

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

createApp(App)
  .use(createPinia())
  .use(router)
  .use(ElementPlus)
  .mount('#app')
```

`src/App.vue`：

```vue
<template>
  <router-view />
</template>
```

`src/api/request.js`：

```js
import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 10000 })

request.interceptors.request.use(config => {
  const token = localStorage.getItem('bw_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  res => {
    const body = res.data
    if (body && body.code === 0) return body
    ElMessage.error((body && body.message) || '请求失败')
    return Promise.reject(new Error((body && body.message) || '请求失败'))
  },
  err => {
    if (err.response && err.response.status === 401) {
      localStorage.removeItem('bw_token')
      localStorage.removeItem('bw_user')
      if (router.currentRoute.value.path !== '/login') {
        router.push('/login')
        ElMessage.warning('登录已过期，请重新登录')
      }
    } else {
      ElMessage.error((err.response && err.response.data && err.response.data.message) || '网络异常')
    }
    return Promise.reject(err)
  }
)

export default request
```

`src/stores/user.js`：

```js
import { defineStore } from 'pinia'
import request from '../api/request'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('bw_token') || '',
    user: JSON.parse(localStorage.getItem('bw_user') || 'null'),
  }),
  actions: {
    async login(form) {
      const res = await request.post('/auth/login', form)
      this.token = res.data.token
      this.user = res.data
      localStorage.setItem('bw_token', this.token)
      localStorage.setItem('bw_user', JSON.stringify(res.data))
    },
    async register(form) {
      await request.post('/auth/register', form)
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('bw_token')
      localStorage.removeItem('bw_user')
    },
  },
})
```

`src/router/index.js`：

```js
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue') },
  { path: '/register', component: () => import('../views/RegisterView.vue') },
  {
    path: '/',
    component: () => import('../layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('../views/PlaceholderView.vue'), meta: { title: '首页仪表盘' } },
      { path: 'record', component: () => import('../views/PlaceholderView.vue'), meta: { title: '记一笔' } },
      { path: 'transactions', component: () => import('../views/PlaceholderView.vue'), meta: { title: '流水' } },
      { path: 'import', component: () => import('../views/PlaceholderView.vue'), meta: { title: '账单导入' } },
      { path: 'accounts', component: () => import('../views/AccountView.vue'), meta: { title: '账户管理' } },
      { path: 'categories', component: () => import('../views/CategoryView.vue'), meta: { title: '分类管理' } },
      { path: 'stats', component: () => import('../views/PlaceholderView.vue'), meta: { title: '统计报表' } },
    ],
  },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(to => {
  const token = localStorage.getItem('bw_token')
  if (!token && to.path !== '/login' && to.path !== '/register') return '/login'
  if (token && (to.path === '/login' || to.path === '/register')) return '/dashboard'
  return true
})

export default router
```

`src/views/PlaceholderView.vue`：

```vue
<template>
  <el-empty :description="`「${$route.meta.title}」将在后续阶段实现`" />
</template>
```

`src/layouts/MainLayout.vue`：

```vue
<template>
  <el-container style="height: 100vh">
    <el-aside width="216px" style="background: #1e2235">
      <div class="logo"><span class="mark">账</span> BalanceWise</div>
      <el-menu :default-active="$route.path" router background-color="#1e2235"
               text-color="#9aa1b9" active-text-color="#ffffff">
        <el-menu-item index="/dashboard"><el-icon><HomeFilled /></el-icon>首页仪表盘</el-menu-item>
        <el-menu-item index="/record"><el-icon><EditPen /></el-icon>记一笔</el-menu-item>
        <el-menu-item index="/transactions"><el-icon><Notebook /></el-icon>流水</el-menu-item>
        <el-menu-item index="/import"><el-icon><Download /></el-icon>账单导入</el-menu-item>
        <el-menu-item index="/accounts"><el-icon><CreditCard /></el-icon>账户管理</el-menu-item>
        <el-menu-item index="/categories"><el-icon><CollectionTag /></el-icon>分类管理</el-menu-item>
        <el-menu-item index="/stats"><el-icon><DataAnalysis /></el-icon>统计报表</el-menu-item>
      </el-menu>
      <div class="logout" @click="doLogout">↩ 退出登录</div>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ $route.meta.title }}</span>
        <span class="nick">{{ store.user?.nickname }}</span>
      </el-header>
      <el-main style="background: #f5f6fa">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUserStore } from '../stores/user'
import {
  HomeFilled, EditPen, Notebook, Download, CreditCard, CollectionTag, DataAnalysis,
} from '@element-plus/icons-vue'

const router = useRouter()
const store = useUserStore()

function doLogout() {
  store.logout()
  router.push('/login')
}
</script>

<style scoped>
.logo { color: #fff; font-size: 17px; font-weight: 700; padding: 20px 18px; }
.logo .mark {
  display: inline-block; width: 26px; height: 26px; border-radius: 8px; line-height: 26px;
  text-align: center; font-size: 14px;
  background: linear-gradient(135deg, #4e63f2, #7b5cf0); margin-right: 6px;
}
.logout {
  color: #9aa1b9; padding: 14px 24px; cursor: pointer; font-size: 14px;
  position: absolute; bottom: 0; width: 216px; box-sizing: border-box;
}
.logout:hover { color: #fff; background: rgba(255, 255, 255, .06); }
.header {
  display: flex; justify-content: space-between; align-items: center;
  background: #fff; border-bottom: 1px solid #e6e8f0;
}
.header .title { font-size: 16px; font-weight: 600; }
.header .nick { color: #8a90a3; font-size: 14px; }
</style>
```

- [ ] **Step 2: 安装依赖并验证可启动**

Run:
```bash
cd frontend
npm install
npm run dev
```
Expected: 输出 `Local: http://localhost:5173/`。浏览器打开（后端未启动不影响骨架验证），应显示登录页或 404（LoginView 在 Task 8 才创建，此刻 `/login` 会报组件不存在——属预期）。`Ctrl+C` 停止。

- [ ] **Step 3: Commit**

```bash
git add frontend
git commit -m "feat(p1): 前端工程骨架（Vite/路由/Pinia/axios/主布局）"
```

---

### Task 8: 前端登录与注册页

**Files:**
- Create: `frontend/src/views/LoginView.vue`
- Create: `frontend/src/views/RegisterView.vue`

**Interfaces:**
- Consumes: Task 7 `useUserStore.login/register`。
- Produces: 登录成功 → `/dashboard`；注册成功 → 提示后跳 `/login`。

- [ ] **Step 1: 写两个页面**

`LoginView.vue`：

```vue
<template>
  <div class="auth-wrap">
    <el-card class="auth-card">
      <div class="brand">
        <div class="mark">账</div>
        <h2>BalanceWise 个人账务管理</h2>
        <p>多账户统一管理 · 账单导入自动记账 · 财务统计可视化</p>
      </div>
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" native-type="submit">登 录</el-button>
      </el-form>
      <div class="links">还没有账号？<router-link to="/register">立即注册</router-link></div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await store.login(form)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-wrap {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #20264a 0%, #3b4fd1 100%);
}
.auth-card { width: 400px; border-radius: 16px; }
.brand { text-align: center; margin-bottom: 24px; }
.brand .mark {
  width: 52px; height: 52px; border-radius: 14px; margin: 0 auto 12px; color: #fff;
  font-size: 26px; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #4e63f2, #7b5cf0);
}
.brand h2 { font-size: 20px; }
.brand p { color: #8a90a3; font-size: 13px; margin-top: 6px; }
.links { text-align: center; margin-top: 18px; color: #8a90a3; font-size: 13px; }
</style>
```

`RegisterView.vue`：

```vue
<template>
  <div class="auth-wrap">
    <el-card class="auth-card">
      <div class="brand">
        <div class="mark">账</div>
        <h2>创建账号</h2>
        <p>注册即为您初始化常用收支分类，开箱即用</p>
      </div>
      <el-form :model="form" @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名（4-32 位字母、数字或下划线）" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.nickname" placeholder="昵称（选填）" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码（至少 6 位）" size="large" show-password />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" native-type="submit">注 册</el-button>
      </el-form>
      <div class="links">已有账号？<router-link to="/login">直接登录</router-link></div>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'

const router = useRouter()
const store = useUserStore()
const loading = ref(false)
const form = reactive({ username: '', nickname: '', password: '', confirmPassword: '' })

async function submit() {
  if (form.password !== form.confirmPassword) {
    ElMessage.error('两次输入的密码不一致')
    return
  }
  loading.value = true
  try {
    await store.register(form)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-wrap {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #20264a 0%, #3b4fd1 100%);
}
.auth-card { width: 400px; border-radius: 16px; }
.brand { text-align: center; margin-bottom: 24px; }
.brand .mark {
  width: 52px; height: 52px; border-radius: 14px; margin: 0 auto 12px; color: #fff;
  font-size: 26px; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #4e63f2, #7b5cf0);
}
.brand h2 { font-size: 20px; }
.brand p { color: #8a90a3; font-size: 13px; margin-top: 6px; }
.links { text-align: center; margin-top: 18px; color: #8a90a3; font-size: 13px; }
</style>
```

- [ ] **Step 2: 启动前后端联调验证**

Run（两个终端）：
```bash
cd backend && mvn spring-boot:run
cd frontend && npm run dev
```
浏览器打开 `http://localhost:5173/register`，注册一个账号（如 `tester01 / 123456`），应跳转登录页；用同账号登录，应进入主布局（dashboard 显示 Placeholder）。
Expected: 注册/登录均成功；数据库 `user` 表新增一行、`category` 表新增 31 行、`account` 表新增一行（现金）。

- [ ] **Step 3: Commit**

```bash
git add frontend
git commit -m "feat(p1): 前端登录与注册页"
```

---

### Task 9: 前端账户管理页

**Files:**
- Create: `frontend/src/views/AccountView.vue`

**Interfaces:**
- Consumes: Task 5 的 `GET /api/accounts`（`AccountSummaryVo`）、`POST/PUT /api/accounts`、`DELETE /api/accounts/{id}`、`PUT /api/accounts/{id}/archive`；Task 7 `request`。
- Produces: 无（页面级）。

- [ ] **Step 1: 写 AccountView.vue**

```vue
<template>
  <div>
    <el-row :gutter="16" style="margin-bottom: 16px">
      <el-col :span="8">
        <el-card><div class="stat"><span class="label">净资产</span>
          <span class="value">¥{{ money(summary.netWorth) }}</span></div></el-card>
      </el-col>
      <el-col :span="8">
        <el-card><div class="stat"><span class="label">资产合计</span>
          <span class="value income">¥{{ money(summary.assetTotal) }}</span></div></el-card>
      </el-col>
      <el-col :span="8">
        <el-card><div class="stat"><span class="label">负债合计（欠款）</span>
          <span class="value expense">¥{{ money(summary.liabilityTotal) }}</span></div></el-card>
      </el-col>
    </el-row>

    <el-card style="margin-bottom: 16px">
      <template #header>
        <div class="card-head">
          <span>资产账户</span>
          <el-button type="primary" size="small" @click="openDialog()">＋ 新建账户</el-button>
        </div>
      </template>
      <AccountRow v-for="a in assets" :key="a.id" :acc="a" @edit="openDialog(a)" @archive="toggleArchive" @remove="remove" />
      <el-empty v-if="!assets.length" description="暂无资产账户" :image-size="60" />
    </el-card>

    <el-card>
      <template #header><span>负债账户</span></template>
      <AccountRow v-for="a in liabilities" :key="a.id" :acc="a" @edit="openDialog(a)" @archive="toggleArchive" @remove="remove" />
      <el-empty v-if="!liabilities.length" description="暂无负债账户" :image-size="60" />
    </el-card>

    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑账户' : '新建账户'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="dialog.name" maxlength="32" placeholder="如：招商储蓄卡" />
        </el-form-item>
        <el-form-item label="类别">
          <el-radio-group v-model="dialog.type">
            <el-radio value="asset">资产</el-radio>
            <el-radio value="liability">负债</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="子类型">
          <el-select v-model="dialog.subType" style="width: 100%">
            <el-option v-for="(label, key) in subTypeOptions" :key="key" :label="label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!dialog.id" label="初始余额">
          <el-input-number v-model="dialog.initialBalance" :min="0" :precision="2" style="width: 100%" />
          <div class="hint">负债账户请填写当前欠款金额</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialog.remark" maxlength="255" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive } from 'vue'
import { ElButton, ElMessage, ElMessageBox, ElTag } from 'element-plus'
import request from '../api/request'

const SUB_TYPES = {
  asset: { cash: '现金', debit_card: '储蓄卡', wechat: '微信零钱', alipay: '支付宝余额', yuebao: '余额宝', custom: '自定义' },
  liability: { credit_card: '信用卡', huabei: '花呗', custom: '自定义' },
}

const summary = reactive({ accounts: [], netWorth: 0, assetTotal: 0, liabilityTotal: 0 })
const assets = computed(() => summary.accounts.filter(a => a.type === 'asset'))
const liabilities = computed(() => summary.accounts.filter(a => a.type === 'liability'))
const subTypeOptions = computed(() => SUB_TYPES[dialog.type] || {})

const dialog = reactive({
  visible: false, saving: false, id: null,
  name: '', type: 'asset', subType: 'cash', initialBalance: 0, remark: '',
})

function money(v) {
  return Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

async function load() {
  const res = await request.get('/accounts')
  Object.assign(summary, res.data)
}

function openDialog(acc) {
  if (acc) {
    Object.assign(dialog, {
      id: acc.id, name: acc.name, type: acc.type,
      subType: acc.subType, initialBalance: 0, remark: acc.remark || '',
    })
  } else {
    Object.assign(dialog, {
      id: null, name: '', type: 'asset', subType: 'cash', initialBalance: 0, remark: '',
    })
  }
  dialog.visible = true
}

async function save() {
  dialog.saving = true
  try {
    const body = {
      name: dialog.name, type: dialog.type, subType: dialog.subType,
      initialBalance: dialog.initialBalance, remark: dialog.remark,
    }
    if (dialog.id) await request.put(`/accounts/${dialog.id}`, body)
    else await request.post('/accounts', body)
    ElMessage.success('已保存')
    dialog.visible = false
    await load()
  } finally {
    dialog.saving = false
  }
}

async function toggleArchive(acc) {
  await request.put(`/accounts/${acc.id}/archive`, { isArchived: !acc.isArchived })
  ElMessage.success(acc.isArchived ? '已恢复' : '已归档')
  await load()
}

async function remove(acc) {
  try {
    await ElMessageBox.confirm(
      acc.isArchived ? `确定删除账户「${acc.name}」？` : `账户「${acc.name}」删除后不可恢复（有交易时会被拦截），确定？`,
      '删除确认', { type: 'warning' }
    )
  } catch { return }
  await request.delete(`/accounts/${acc.id}`)
  ElMessage.success('已删除')
  await load()
}

// 行内子组件：名称 + 子类型 + 余额 + 操作按钮
const AccountRow = (props, { emit }) => {
  const acc = props.acc
  const label = (SUB_TYPES[acc.type] || {})[acc.subType] || acc.subType
  const display = acc.type === 'liability' ? -Number(acc.balance) : Number(acc.balance)
  return h('div', { class: 'acc-row' }, [
    h('div', null, [
      h('span', { class: 'acc-name' }, acc.name),
      h(ElTag, { size: 'small', style: 'margin-left:8px' }, () => label),
      acc.isArchived ? h(ElTag, { size: 'small', type: 'info', style: 'margin-left:6px' }, () => '已归档') : null,
    ]),
    h('div', { class: 'acc-ops' }, [
      h('span', { class: ['acc-balance', acc.type === 'liability' ? 'expense' : ''] },
        `¥${money(display)}`),
      h(ElButton, { size: 'small', onClick: () => emit('edit', acc) }, () => '编辑'),
      h(ElButton, { size: 'small', onClick: () => emit('archive', acc) }, () => (acc.isArchived ? '恢复' : '归档')),
      h(ElButton, { size: 'small', type: 'danger', plain: true, onClick: () => emit('remove', acc) }, () => '删除'),
    ]),
  ])
}
AccountRow.props = { acc: { type: Object, required: true } }

onMounted(load)
</script>

<style scoped>
.stat { display: flex; flex-direction: column; gap: 6px; }
.stat .label { color: #8a90a3; font-size: 13px; }
.stat .value { font-size: 24px; font-weight: 700; }
.stat .income { color: #3aa97a; }
.stat .expense { color: #e8604c; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: 12px; color: #8a90a3; line-height: 1.4; }
:deep(.acc-row) {
  display: flex; justify-content: space-between; align-items: center;
  padding: 12px 4px; border-bottom: 1px solid #f0f1f5;
}
:deep(.acc-row:last-child) { border-bottom: none; }
:deep(.acc-name) { font-weight: 600; }
:deep(.acc-ops) { display: flex; align-items: center; gap: 10px; }
:deep(.acc-balance) { font-variant-numeric: tabular-nums; font-weight: 600; }
:deep(.acc-balance.expense) { color: #e8604c; }
</style>
```

- [ ] **Step 2: 手工验证**

前置：Task 8 已启动前后端。浏览器进 `http://localhost:5173/accounts`：
1. 新建资产账户"招商储蓄卡"，初始余额 10000 → 出现在资产列表，净资产 +10000；
2. 新建负债账户"花呗"，初始余额（欠款）500 → 负债合计 500.00 红色显示，净资产相应减少；
3. 编辑账户改名 → 保存生效；
4. 归档账户 → 出现"已归档"标记，恢复可还原；
5. 删除新建的账户（无交易）成功；删除"现金"账户（有默认流水？无）——注意：本阶段还没有记账功能，任何账户都还没交易记录，均可删除。拦截逻辑由后端测试保障（AccountService.delete 中的 countByAccount 检查），Task 11 会用 SQL 造数据验证一次。
Expected: 全部符合预期；后端日志无异常。

- [ ] **Step 3: Commit**

```bash
git add frontend
git commit -m "feat(p1): 前端账户管理页（汇总卡片/CRUD/归档/删除）"
```

---

### Task 10: 前端分类管理页

**Files:**
- Create: `frontend/src/views/CategoryView.vue`

**Interfaces:**
- Consumes: Task 6 的 `GET /api/categories?kind=`、`POST /api/categories`、`PUT /api/categories/{id}`、`DELETE /api/categories/{id}`。
- Produces: 无（页面级）。

- [ ] **Step 1: 写 CategoryView.vue**

```vue
<template>
  <el-row :gutter="16">
    <el-col :span="12">
      <el-card>
        <template #header>
          <div class="card-head">
            <span>支出分类</span>
            <el-button type="primary" size="small" @click="openCreate('expense', null)">＋ 一级分类</el-button>
          </div>
        </template>
        <div v-for="g in expenseTree" :key="g.id" class="cat-group">
          <div class="cat-parent">
            <span>{{ g.name }} <el-tag v-if="g.isSystem === 1" size="small" type="info" style="margin-left:6px">预置</el-tag></span>
            <span>
              <el-button size="small" text @click="openCreate('expense', g)">＋子类</el-button>
              <el-button size="small" text @click="openRename(g)">改名</el-button>
              <el-button size="small" text type="danger" @click="remove(g)">删除</el-button>
            </span>
          </div>
          <div class="cat-children">
            <span v-for="c in g.children" :key="c.id" class="cat-child">
              {{ c.name }}
              <el-button size="small" text @click="openRename(c)">改名</el-button>
              <el-button size="small" text type="danger" @click="remove(c)">删除</el-button>
            </span>
            <span v-if="!g.children.length" class="muted">（无子分类）</span>
          </div>
        </div>
      </el-card>
    </el-col>
    <el-col :span="12">
      <el-card>
        <template #header>
          <div class="card-head">
            <span>收入分类</span>
            <el-button type="primary" size="small" @click="openCreate('income', null)">＋ 一级分类</el-button>
          </div>
        </template>
        <div v-for="g in incomeTree" :key="g.id" class="cat-group">
          <div class="cat-parent">
            <span>{{ g.name }} <el-tag v-if="g.isSystem === 1" size="small" type="info" style="margin-left:6px">预置</el-tag></span>
            <span>
              <el-button size="small" text @click="openCreate('income', g)">＋子类</el-button>
              <el-button size="small" text @click="openRename(g)">改名</el-button>
              <el-button size="small" text type="danger" @click="remove(g)">删除</el-button>
            </span>
          </div>
          <div class="cat-children">
            <span v-for="c in g.children" :key="c.id" class="cat-child">
              {{ c.name }}
              <el-button size="small" text @click="openRename(c)">改名</el-button>
              <el-button size="small" text type="danger" @click="remove(c)">删除</el-button>
            </span>
            <span v-if="!g.children.length" class="muted">（无子分类）</span>
          </div>
        </div>
      </el-card>
    </el-col>
  </el-row>

  <el-dialog v-model="dlg.visible" :title="dlg.title" width="400px">
    <el-input v-model="dlg.name" maxlength="32" placeholder="分类名称" />
    <template #footer>
      <el-button @click="dlg.visible = false">取消</el-button>
      <el-button type="primary" @click="save">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api/request'

const expenseTree = ref([])
const incomeTree = ref([])
const dlg = reactive({ visible: false, title: '', mode: 'create', kind: 'expense', id: null, parentId: null, name: '' })

async function load() {
  const [e, i] = await Promise.all([
    request.get('/categories', { params: { kind: 'expense' } }),
    request.get('/categories', { params: { kind: 'income' } }),
  ])
  expenseTree.value = e.data
  incomeTree.value = i.data
}

function openCreate(kind, parent) {
  Object.assign(dlg, {
    visible: true, mode: 'create', kind, id: null,
    parentId: parent ? parent.id : null,
    name: '',
    title: parent ? `在「${parent.name}」下新增子分类` : `新增${kind === 'expense' ? '支出' : '收入'}一级分类`,
  })
}

function openRename(cat) {
  Object.assign(dlg, {
    visible: true, mode: 'rename', kind: cat.kind, id: cat.id,
    parentId: null, name: cat.name, title: '修改分类名称',
  })
}

async function save() {
  if (!dlg.name.trim()) {
    ElMessage.warning('请输入分类名称')
    return
  }
  if (dlg.mode === 'create') {
    await request.post('/categories', { name: dlg.name.trim(), kind: dlg.kind, parentId: dlg.parentId })
  } else {
    await request.put(`/categories/${dlg.id}`, { name: dlg.name.trim() })
  }
  ElMessage.success('已保存')
  dlg.visible = false
  await load()
}

async function remove(cat) {
  try {
    await ElMessageBox.confirm(`确定删除分类「${cat.name}」？（预置分类或被交易使用的分类将被拦截）`, '删除确认', { type: 'warning' })
  } catch { return }
  await request.delete(`/categories/${cat.id}`)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
.cat-group { margin-bottom: 10px; }
.cat-parent {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 10px; background: #fafbfd; border-radius: 8px; font-weight: 600;
}
.cat-children { padding: 6px 0 0 16px; display: flex; flex-wrap: wrap; gap: 4px 12px; align-items: center; }
.cat-child { color: #606266; font-size: 13px; }
.muted { color: #8a90a3; font-size: 12px; }
</style>
```

- [ ] **Step 2: 手工验证**

浏览器进 `http://localhost:5173/categories`：
1. 两类预置分类完整显示（支出 9 组、收入 5 组），预置标记正确；
2. 支出下新增一级分类"测试类" → 成功；在其下新增子类"子A" → 成功；
3. 对"子A"改名 → 生效；对"外卖"改名 → 生效（预置可改名）；
4. 删除"外卖" → 弹后端错误"预置分类不可删除"；删除刚建的"测试类"（无子类无交易）→ 成功；有子类的"测试类"删除 → 提示先删子类；
5. 新建与"外卖"同名的子类（餐饮下）→ 弹"同级下已存在同名分类"。
Expected: 拦截与放行全部符合规则，错误消息以 ElMessage 弹出。

- [ ] **Step 3: Commit**

```bash
git add frontend
git commit -m "feat(p1): 前端分类管理页（两级树/增改删/规则拦截提示）"
```

---

### Task 11: P1 端到端验证与阶段收尾

**Files:**
- Modify: `README.md`（项目根，新增）

- [ ] **Step 1: 后端全量回归**

Run: `cd backend && mvn test`
Expected: 20/20 通过（JwtUtil 3 + AuthApi 3 + User 4 + Account 5 + Category 5）。

- [ ] **Step 2: 删除保护端到端验证（真实 MySQL）**

用注册过的账号在页面上建一个账户（如"测试卡"），然后直接往库里插一条引用它的流水，再从页面删除该账户，验证拦截：

```bash
mysql -u root -p balancewise -e "
INSERT INTO transaction_record (user_id, type, from_account_id, amount, occurred_at, source, is_deleted)
SELECT user_id, 'expense', <测试卡ID>, 10.00, NOW(), 'manual', 0 FROM account WHERE name='测试卡';
"
```
（`<测试卡ID>` 用 `SELECT id, name FROM account;` 查）
然后在页面删除"测试卡" → Expected: 弹出"该账户存在交易记录，无法删除，可选择归档"。验证完清理：
```bash
mysql -u root -p balancewise -e "DELETE FROM transaction_record; DELETE FROM account WHERE name='测试卡';"
```

- [ ] **Step 3: 编写 README 运行说明**

`README.md`：

```markdown
# BalanceWise 个人账务管理与统计分析系统

基于 Spring Boot 3 + Vue 3 的多用户个人账务管理系统（毕业论文项目）。

## 技术栈
后端：Java 17、Spring Boot 3.3、MyBatis-Plus 3.5、MySQL 8、JWT
前端：Vue 3、Vite、Element Plus、ECharts、Pinia

## 本地运行
1. 创建数据库：`CREATE DATABASE balancewise DEFAULT CHARSET utf8mb4;`
   并执行 `backend/src/main/resources/schema.sql` 建表。
2. 修改 `backend/src/main/resources/application.yml` 中的数据库账号密码。
3. 启动后端：`cd backend && mvn spring-boot:run`（端口 8080）
4. 启动前端：`cd frontend && npm install && npm run dev`（端口 5173）
5. 浏览器打开 http://localhost:5173 ，注册账号即可使用。

## 运行测试
`cd backend && mvn test`（使用 H2 内存库，无需 MySQL）

## 项目文档
需求与设计规格：`docs/superpowers/specs/2026-10-21-balancewise-design.md`
实施计划：`docs/superpowers/plans/2026-10-22-phase1-foundation.md`
界面原型：`prototype/`（纯静态可点击原型）
```

- [ ] **Step 4: 阶段验收 + 打 tag**

逐项确认（P1 验收标准）：
- [ ] 注册新账号：自动初始化 31 个分类 + 1 个现金账户
- [ ] 登录/退出正常，无 token 访问任意页面被重定向到登录页
- [ ] 账户：新建（资产正余额/负债负余额）、编辑、归档/恢复、删除、净资产汇总正确
- [ ] 分类：两级树展示、新增一/二级、改名、三类删除拦截（预置/有子类/被引用）
- [ ] 后端测试 20/20 通过

```bash
git add README.md
git commit -m "docs(p1): README 运行说明"
git tag p1-foundation
```

- [ ] **Step 5: 汇报并等待阶段评审**

向项目所有者汇报本阶段完成情况，评审通过后开始编写 P2（记账模块：余额联动 / 冲销事务）计划。
