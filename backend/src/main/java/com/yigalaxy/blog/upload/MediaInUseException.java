package com.yigalaxy.blog.upload;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class MediaInUseException extends RuntimeException {
    public MediaInUseException(String filename) { super("媒体文件仍被内容引用，不能删除：" + filename); }
}
