/*
 * Minimal fallback for the com.mongodb.annotations.Immutable annotation to allow
 * Maven compilation when the driver annotations are not present. If the real
 * driver annotation is on the classpath, it will be used instead.
 */
package com.mongodb.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE})
public @interface Immutable {
}
