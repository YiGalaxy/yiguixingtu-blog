package com.yigalaxy.blog.upload;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class MediaNotFoundException extends RuntimeException {
    public MediaNotFoundException(String filename) { super("媒体文件不存在：" + filename); }
}
