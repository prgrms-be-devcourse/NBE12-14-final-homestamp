package com.chilsuitjo.back.global.config.ssm;

/**
 * A production settings failure whose message is safe to show: it names keys, AWS error codes or
 * exception class names, never values, URLs or SDK messages.
 *
 * <p>It never carries a cause. An SDK exception's message and stack trace can contain request
 * details, so the original exception is dropped on purpose and only its class name or error code is
 * kept in the message.
 */
public final class SsmConfigException extends IllegalStateException {

  SsmConfigException(String message) {
    super(message, null);
  }
}
