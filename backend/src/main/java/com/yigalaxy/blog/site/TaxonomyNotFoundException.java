package com.yigalaxy.blog.site;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class TaxonomyNotFoundException extends RuntimeException { }
