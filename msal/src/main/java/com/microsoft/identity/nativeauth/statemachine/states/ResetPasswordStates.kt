//  Copyright (c) Microsoft Corporation.
//  All rights reserved.
//
//  This code is licensed under the MIT License.
//
//  Permission is hereby granted, free of charge, to any person obtaining a copy
//  of this software and associated documentation files(the "Software"), to deal
//  in the Software without restriction, including without limitation the rights
//  to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
//  copies of the Software, and to permit persons to whom the Software is
//  furnished to do so, subject to the following conditions :
//
//  The above copyright notice and this permission notice shall be included in
//  all copies or substantial portions of the Software.
//
//  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
//  THE SOFTWARE.

package com.microsoft.identity.nativeauth.statemachine.states

import com.microsoft.identity.nativeauth.NativeAuthPublicClientApplication
import com.microsoft.identity.nativeauth.NativeAuthPublicClientApplicationConfiguration
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.internal.CommandParametersAdapter
import com.microsoft.identity.nativeauth.statemachine.results.ResetPasswordResendCodeResult
import com.microsoft.identity.nativeauth.statemachine.results.ResetPasswordResult
import com.microsoft.identity.nativeauth.statemachine.results.ResetPasswordSubmitCodeResult
import com.microsoft.identity.nativeauth.statemachine.results.ResetPasswordSubmitPasswordResult
import com.microsoft.identity.common.nativeauth.internal.commands.ResetPasswordResendCodeCommand
import com.microsoft.identity.common.nativeauth.internal.commands.ResetPasswordSubmitCodeCommand
import com.microsoft.identity.common.nativeauth.internal.commands.ResetPasswordSubmitNewPasswordCommand
import com.microsoft.identity.common.nativeauth.internal.controllers.NativeAuthMsalController
import com.microsoft.identity.common.java.controllers.CommandDispatcher
import com.microsoft.identity.common.java.nativeauth.controllers.results.INativeAuthCommandResult
import com.microsoft.identity.common.java.nativeauth.controllers.results.ResetPasswordCommandResult
import com.microsoft.identity.common.java.nativeauth.controllers.results.ResetPasswordResendCodeCommandResult
import com.microsoft.identity.common.java.nativeauth.controllers.results.ResetPasswordSubmitCodeCommandResult
import com.microsoft.identity.common.java.nativeauth.controllers.results.ResetPasswordSubmitNewPasswordCommandResult
import com.microsoft.identity.common.java.eststelemetry.PublicApiId
import com.microsoft.identity.common.java.logging.LogSession
import com.microsoft.identity.common.java.logging.Logger
import com.microsoft.identity.common.java.util.StringUtil
import com.microsoft.identity.common.java.nativeauth.util.checkAndWrapCommandResultType
import com.microsoft.identity.nativeauth.statemachine.errors.ErrorTypes
import com.microsoft.identity.nativeauth.statemachine.errors.ResendCodeError
import com.microsoft.identity.nativeauth.statemachine.errors.ResetPasswordErrorTypes
import com.microsoft.identity.nativeauth.statemachine.errors.ResetPasswordSubmitPasswordError
import com.microsoft.identity.nativeauth.statemachine.errors.SubmitCodeError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/**
 * Native Auth uses a state machine to denote state of and transitions within a flow.
 * ResetPasswordCodeRequiredState class represents a state where the user has to provide a code to progress
 * in the reset password flow.
 * @property flowToken: Flow token to be passed in the next request
 * @property config Configuration used by Native Auth
 */
class ResetPasswordCodeRequiredState internal constructor(
    override val flowToken: String,
    private val config: NativeAuthPublicClientApplicationConfiguration
) : BaseState(flowToken), State, Serializable {
    private val TAG: String = ResetPasswordCodeRequiredState::class.java.simpleName

    interface SubmitCodeCallback : Callback<ResetPasswordSubmitCodeResult>

    /**
     * Submits the verification code received to the server; callback variant.
     *
     * @param code The code to submit.
     * @param callback [com.microsoft.identity.nativeauth.statemachine.states.ResetPasswordPasswordRequiredState.SubmitPasswordCallback] to receive the result on.
     * @return The results of the submit code action.
     */
    fun submitCode(code: String, callback: SubmitCodeCallback) {
        LogSession.logMethodCall(TAG, "${TAG}.submitCode")
        NativeAuthPublicClientApplication.pcaScope.launch {
            try {
                val result = submitCode(code = code)
                callback.onResult(result)
            } catch (e: MsalException) {
                Logger.error(TAG, "Exception thrown in submitCode", e)
                callback.onError(e)
            }
        }
    }

    /**
     * Submits the verification code received to the server; Kotlin coroutines variant.
     *
     * @param code The code to submit.
     * @return The results of the submit code action.
     */
    suspend fun submitCode(code: String): ResetPasswordSubmitCodeResult {
        LogSession.logMethodCall(TAG, "${TAG}.submitCode(code: String)")
        return withContext(Dispatchers.IO) {
            val parameters =
                CommandParametersAdapter.createResetPasswordSubmitCodeCommandParameters(
                    config,
                    config.oAuth2TokenCache,
                    code,
                    flowToken
                )

            val command = ResetPasswordSubmitCodeCommand(
                parameters = parameters,
                controller = NativeAuthMsalController(),
                PublicApiId.NATIVE_AUTH_RESET_PASSWORD_SUBMIT_CODE
            )

            val rawCommandResult = CommandDispatcher.submitSilentReturningFuture(command).get()

            return@withContext when (val result = rawCommandResult.checkAndWrapCommandResultType<ResetPasswordSubmitCodeCommandResult>()) {
                is ResetPasswordCommandResult.PasswordRequired -> {
                    ResetPasswordSubmitCodeResult.PasswordRequired(
                        nextState = ResetPasswordPasswordRequiredState(
                            flowToken = result.passwordSubmitToken,
                            config = config
                        )
                    )
                }

                is ResetPasswordCommandResult.IncorrectCode -> {
                    SubmitCodeError(
                        errorType = ErrorTypes.INVALID_CODE,
                        error = result.error,
                        errorMessage = result.errorDescription,
                        correlationId = result.correlationId
                    )
                }

                is INativeAuthCommandResult.Redirect -> {
                    SubmitCodeError(
                        errorType = ErrorTypes.BROWSER_REQUIRED,
                        error = result.error,
                        errorMessage = result.errorDescription,
                        correlationId = result.correlationId
                    )
                }

                is INativeAuthCommandResult.UnknownError -> {
                    Logger.warn(
                        TAG,
                        "Submit code received unexpected result: $result"
                    )
                    SubmitCodeError(
                        errorMessage = result.errorDescription,
                        error = result.error,
                        correlationId = result.correlationId,
                        exception = result.exception
                    )
                }
            }
        }
    }

    /**
     * ResendCodeCallback receives the result for submit code for Reset Password for Native Auth
     */
    interface ResendCodeCallback : Callback<ResetPasswordResendCodeResult>

    /**
     * Resends a new verification code to the user; callback variant.
     *
     * @param callback [com.microsoft.identity.nativeauth.statemachine.states.ResetPasswordCodeRequiredState.ResendCodeCallback] to receive the result on.
     * @return The results of the resend code action.
     */
    fun resendCode(callback: ResendCodeCallback) {
        LogSession.logMethodCall(TAG, "${TAG}.resendCode(callback: ResendCodeCallback)")
        NativeAuthPublicClientApplication.pcaScope.launch {
            try {
                val result = resendCode()
                callback.onResult(result)
            } catch (e: MsalException) {
                Logger.error(TAG, "Exception thrown in resendCode", e)
                callback.onError(e)
            }
        }
    }

    /**
     * Resends a new verification code to the user; Kotlin coroutines variant.
     *
     * @return The results of the resend code action.
     */
    suspend fun resendCode(): ResetPasswordResendCodeResult {
        LogSession.logMethodCall(TAG, "${TAG}.resendCode")
        return withContext(Dispatchers.IO) {
            val parameters =
                CommandParametersAdapter.createResetPasswordResendCodeCommandParameters(
                    config,
                    config.oAuth2TokenCache,
                    flowToken
                )

            val command = ResetPasswordResendCodeCommand(
                parameters = parameters,
                controller = NativeAuthMsalController(),
                PublicApiId.NATIVE_AUTH_RESET_PASSWORD_RESEND_CODE
            )

            val rawCommandResult = CommandDispatcher.submitSilentReturningFuture(command).get()

            return@withContext when (val result = rawCommandResult.checkAndWrapCommandResultType<ResetPasswordResendCodeCommandResult>()) {
                is ResetPasswordCommandResult.CodeRequired -> {
                    ResetPasswordResendCodeResult.Success(
                        nextState = ResetPasswordCodeRequiredState(
                            flowToken = result.passwordResetToken,
                            config = config
                        ),
                        codeLength = result.codeLength,
                        sentTo = result.challengeTargetLabel,
                        channel = result.challengeChannel
                    )
                }

                is INativeAuthCommandResult.Redirect -> {
                    ResendCodeError(
                        errorType = ErrorTypes.BROWSER_REQUIRED,
                        error = result.error,
                        errorMessage = result.errorDescription,
                        correlationId = result.correlationId
                    )
                }

                is INativeAuthCommandResult.UnknownError -> {
                    Logger.warn(
                        TAG,
                        "Resend code received unexpected result: $result"
                    )
                    ResendCodeError(
                        errorMessage = result.errorDescription,
                        error = result.error,
                        correlationId = result.correlationId,
                        exception = result.exception
                    )
                }
            }
        }
    }
}

/**
 * Native Auth uses a state machine to denote state of and transitions within a flow.
 * ResetPasswordPasswordRequiredState class represents a state where the user has to provide a password to progress
 * in the reset password flow.
 * @property flowToken: Flow token to be passed in the next request
 * @property config Configuration used by Native Auth
 */
class ResetPasswordPasswordRequiredState internal constructor(
    override val flowToken: String,
    private val config: NativeAuthPublicClientApplicationConfiguration
) : BaseState(flowToken), State, Serializable {
    private val TAG: String = ResetPasswordPasswordRequiredState::class.java.simpleName

    interface SubmitPasswordCallback : Callback<ResetPasswordSubmitPasswordResult>

    /**
     * Submits a new password to the server; callback variant.
     *
     * @param password The password to submit.
     * @param callback [com.microsoft.identity.nativeauth.statemachine.states.ResetPasswordPasswordRequiredState.SubmitPasswordCallback] to receive the result on.
     * @return The results of the submit password action.
     */
    fun submitPassword(password: CharArray, callback: SubmitPasswordCallback) {
        LogSession.logMethodCall(TAG, "${TAG}.submitPassword")
        NativeAuthPublicClientApplication.pcaScope.launch {
            try {
                val result = submitPassword(password = password)
                callback.onResult(result)
            } catch (e: MsalException) {
                Logger.error(TAG, "Exception thrown in submitPassword", e)
                callback.onError(e)
            }
        }
    }

    /**
     * Submits a new password to the server; Kotlin coroutines variant.
     *
     * @param password The password to submit.
     * @return The results of the submit password action.
     */
    suspend fun submitPassword(password: CharArray): ResetPasswordSubmitPasswordResult {
        LogSession.logMethodCall(TAG, "${TAG}.submitPassword(password: String)")
        return withContext(Dispatchers.IO) {
            val parameters =
                CommandParametersAdapter.createResetPasswordSubmitNewPasswordCommandParameters(
                    config,
                    config.oAuth2TokenCache,
                    flowToken,
                    password
                )

            val command = ResetPasswordSubmitNewPasswordCommand(
                parameters = parameters,
                controller = NativeAuthMsalController(),
                PublicApiId.NATIVE_AUTH_RESET_PASSWORD_SUBMIT_NEW_PASSWORD
            )

            try {
                val rawCommandResult = CommandDispatcher.submitSilentReturningFuture(command).get()

                return@withContext when (val result =
                    rawCommandResult.checkAndWrapCommandResultType<ResetPasswordSubmitNewPasswordCommandResult>()) {
                    is ResetPasswordCommandResult.Complete -> {
                        ResetPasswordResult.Complete
                    }

                    is ResetPasswordCommandResult.PasswordNotAccepted -> {
                        ResetPasswordSubmitPasswordError(
                            errorType = ErrorTypes.INVALID_PASSWORD,
                            error = result.error,
                            errorMessage = result.errorDescription,
                            correlationId = result.correlationId
                        )
                    }

                    is ResetPasswordCommandResult.PasswordResetFailed -> {
                        ResetPasswordSubmitPasswordError(
                            errorType = ResetPasswordErrorTypes.PASSWORD_RESET_FAILED,
                            error = result.error,
                            errorMessage = result.errorDescription,
                            correlationId = result.correlationId
                        )
                    }

                    is ResetPasswordCommandResult.UserNotFound -> {
                        Logger.warn(
                            TAG,
                            "Submit password received unexpected result: $result"
                        )
                        ResetPasswordSubmitPasswordError(
                            error = result.error,
                            errorMessage = result.errorDescription,
                            correlationId = result.correlationId
                        )
                    }

                    is INativeAuthCommandResult.UnknownError -> {
                        Logger.warn(
                            TAG,
                            "Submit password received unexpected result: $result"
                        )
                        ResetPasswordSubmitPasswordError(
                            errorMessage = result.errorDescription,
                            error = result.error,
                            correlationId = result.correlationId,
                            exception = result.exception
                        )
                    }
                }
            } finally {
                StringUtil.overwriteWithNull(parameters.newPassword)
            }
        }
    }
}
