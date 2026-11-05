package com.yigalaxy.blog.site;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class SiteItemNotFoundException extends RuntimeException {
    public SiteItemNotFoundException(long id) { super("站点内容不存在：" + id); }
}
