package com.nopalou;

import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** 认证加密故障及每日计数边界；系统 Keystore 本身另留真机验证。 */
public final class Step50Test {
    static int count;
    static void check(String name, boolean ok) { if(!ok) throw new AssertionError(name); count++; System.out.println("PASS "+name); }
    interface Attempt { void run() throws Exception; }
    static boolean rejects(Attempt a) { try { a.run(); return false; } catch(Exception expected) { return true; } }
    public static void main(String[] args) throws Exception {
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);
        SecretKey key=generator.generateKey(), other=generator.generateKey();
        String secret="local-test-凭据-only"; byte[] a=SecretCodec.encrypt(key,secret), b=SecretCodec.encrypt(key,secret);
        check("密文可往返 Unicode",secret.equals(SecretCodec.decrypt(key,a)));
        check("每次加密使用不同随机数",!Arrays.equals(a,b));
        check("错误密钥不能解密",rejects(()->SecretCodec.decrypt(other,a)));
        byte[] broken=a.clone();broken[broken.length-1]^=1;
        check("篡改被认证标签拒绝",rejects(()->SecretCodec.decrypt(key,broken)));
        check("截断密文被拒绝",rejects(()->SecretCodec.decrypt(key,Arrays.copyOf(a,12))));
        AiBudgetCore budget=new AiBudgetCore();Calendar day=Calendar.getInstance();day.clear();day.set(2026,10,9,12,0);long now=day.getTimeInMillis();
        check("第一个请求可预留",budget.reserve(now,2));
        check("失败也不返还计数",budget.reserve(now,2)&&!budget.reserve(now,2)&&budget.calls==2);
        budget.tokens(45);budget.tokens(-1);check("只累计非负已报告用量",budget.reportedTokens==45);
        day.add(Calendar.DAY_OF_MONTH,1);check("次日恢复计数",budget.reserve(day.getTimeInMillis(),2)&&budget.calls==1&&budget.reportedTokens==0);
        check("上限有界",AiBudgetCore.limit(0)==1&&AiBudgetCore.limit(9999)==500);
        System.out.println("PASS "+count);
    }
}
