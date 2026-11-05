package com.yigalaxy.blog.site;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class TaxonomyInUseException extends RuntimeException {
    public TaxonomyInUseException(String message) { super(message); }
}
