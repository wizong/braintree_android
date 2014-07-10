package com.braintreepayments.api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.braintreepayments.api.data.BraintreeData;
import com.braintreepayments.api.data.BraintreeEnvironment;
import com.braintreepayments.api.exceptions.AuthenticationException;
import com.braintreepayments.api.exceptions.AuthorizationException;
import com.braintreepayments.api.exceptions.BraintreeException;
import com.braintreepayments.api.exceptions.ConfigurationException;
import com.braintreepayments.api.exceptions.DownForMaintenanceException;
import com.braintreepayments.api.exceptions.ErrorWithResponse;
import com.braintreepayments.api.exceptions.ServerException;
import com.braintreepayments.api.exceptions.UnexpectedException;
import com.braintreepayments.api.exceptions.UpgradeRequiredException;
import com.braintreepayments.api.internal.HttpRequest;
import com.braintreepayments.api.internal.HttpRequest.HttpMethod;
import com.braintreepayments.api.internal.HttpRequestFactory;
import com.braintreepayments.api.models.AnalyticsRequest;
import com.braintreepayments.api.models.PayPalAccount;
import com.braintreepayments.api.models.PayPalAccountBuilder;
import com.braintreepayments.api.models.PaymentMethod;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;

/**
 * Synchronous communication with the Braintree gateway. Consumed by {@link Braintree}.
 * Unless synchronous behavior is needed, we recommend using {@link Braintree}.
 *
 * @see Braintree
 */
public class BraintreeApi {

    private static String PAYMENT_METHOD_ENDPOINT = "payment_methods";

    private Context mContext;
    private ClientToken mClientToken;
    private HttpRequestFactory mHttpRequestFactory;

    private BraintreeData mBraintreeData;

    public BraintreeApi(Context context, String clientToken) {
        this(context, ClientToken.getClientToken(clientToken));
    }

    protected BraintreeApi(Context context, ClientToken token) {
        this(context, token, new HttpRequestFactory(context));
    }

    protected BraintreeApi(Context context, ClientToken token,
            HttpRequestFactory requestFactory) {
        mContext = context;
        mClientToken = token;
        mHttpRequestFactory = requestFactory;
    }

    /**
     * @return If PayPal is supported and enabled in the current environment.
     */
    public boolean isPayPalEnabled() {
        return mClientToken.isPayPalEnabled();
    }

    /**
     * @return If cvv is required to add a card
     */
    public boolean isCvvChallengePresent() {
        return mClientToken.isCvvChallengePresent();
    }

    /**
     * @return If postal code is required to add a card
     */
    public boolean isPostalCodeChallengePresent() {
        return mClientToken.isPostalCodeChallengePresent();
    }

    /**
     * Start the Pay With PayPal flow. This will launch a new activity for the PayPal mobile SDK.
     * @param activity The {@link android.app.Activity} to receive {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}
     *   when payWithPayPal finishes
     * @param requestCode The request code associated with this start request. Will be returned in {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}
     */
    public void startPayWithPayPal(Activity activity, int requestCode) {
        PayPalHelper.startPaypal(activity.getApplicationContext(), mClientToken);
        PayPalHelper.launchPayPal(activity, requestCode);
    }

    protected PayPalAccountBuilder handlePayPalResponse(int resultCode, Intent data)
            throws ConfigurationException {
        PayPalHelper.stopPaypalService(mContext);
        return PayPalHelper.getBuilderFromActivity(resultCode, data);
    }

    /**
     *
     * @param resultCode The result code provided in {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}
     * @param data The {@link android.content.Intent} provided in {@link android.app.Activity#onActivityResult(int, int, android.content.Intent)}
     * @return The {@link com.braintreepayments.api.models.PaymentMethod} created from a PayPal account
     * @throws ErrorWithResponse If creation fails validation
     * @throws BraintreeException If an error not due to validation (server error, network issue, etc.) occurs
     * @throws ConfigurationException If PayPal credentials from the Braintree control panel are incorrect
     *
     * @see BraintreeApi#create(com.braintreepayments.api.models.PaymentMethod.Builder)
     */
    public PayPalAccount finishPayWithPayPal(int resultCode, Intent data)
            throws BraintreeException, ErrorWithResponse {
        PayPalAccountBuilder payPalAccountBuilder = handlePayPalResponse(resultCode, data);
        if (payPalAccountBuilder != null) {
            return create(payPalAccountBuilder);
        } else {
            return null;
        }
    }

    /**
     * Create a {@link com.braintreepayments.api.models.PaymentMethod} in the Braintree Gateway.
     *
     * @param paymentMethodBuilder {@link com.braintreepayments.api.models.PaymentMethod.Builder} for the
     * {@link com.braintreepayments.api.models.PaymentMethod} to be created.
     * @param <T> {@link com.braintreepayments.api.models.PaymentMethod} or a subclass.
     * @return {@link com.braintreepayments.api.models.PaymentMethod}
     * @throws ErrorWithResponse If creation fails validation
     * @throws BraintreeException If an error not due to validation (server error, network issue, etc.) occurs
     *
     * @see BraintreeApi#tokenize(com.braintreepayments.api.models.PaymentMethod.Builder)
     */
    public <T extends PaymentMethod> T create(PaymentMethod.Builder<T> paymentMethodBuilder)
            throws ErrorWithResponse, BraintreeException {
        Map<String, Object> params = Utils.newHashMap();
        params.putAll(getDefaultParameters());
        params.putAll(paymentMethodBuilder.toJson());

        HttpRequest request = POST(pathForPaymentMethodCreate(paymentMethodBuilder))
                .rawBody(Utils.getGson().toJson(params))
                .execute();

        checkAndThrowErrors(request);

        return paymentMethodBuilder
                .fromJson(jsonForType(request.response(), paymentMethodBuilder.getApiResource()));
    }

    /**
     * Tokenize a {@link com.braintreepayments.api.models.PaymentMethod} with the Braintree gateway.
     *
     * Tokenization functions like creating a {@link com.braintreepayments.api.models.PaymentMethod}, but
     * defers validation until a server library attempts to use the {@link com.braintreepayments.api.models.PaymentMethod}.
     * Use {@link #tokenize(com.braintreepayments.api.models.PaymentMethod.Builder)} to handle validation errors
     * on the server instead of on device.
     *
     * @param paymentMethodBuilder The {@link com.braintreepayments.api.models.PaymentMethod.Builder} to tokenize
     * @return A nonce that can be used by a server library to create a transaction with the Braintree gateway.
     * @throws BraintreeException
     * @throws ErrorWithResponse
     * @see #create(com.braintreepayments.api.models.PaymentMethod.Builder)
     */
    public String tokenize(PaymentMethod.Builder paymentMethodBuilder)
            throws BraintreeException, ErrorWithResponse {
        PaymentMethod paymentMethod = create(paymentMethodBuilder.validate(false));
        return paymentMethod.getNonce();
    }

    /**
     * @return A {@link java.util.List} of {@link com.braintreepayments.api.models.PaymentMethod}s for this
     *   client token.
     * @throws ErrorWithResponse When a recoverable validation error occurs.
     * @throws BraintreeException When a non-recoverable error (authentication, server error, network, etc.) occurs.
     */
    public List<PaymentMethod> getPaymentMethods() throws ErrorWithResponse, BraintreeException {
        HttpRequest builder = GET(PAYMENT_METHOD_ENDPOINT).execute();

        checkAndThrowErrors(builder);
        return PaymentMethod.parsePaymentMethods(builder.response());
    }

    /**
     * Enqueues analytics events to send to the Braintree analytics service. Used internally and by Drop-In.
     * Analytics events are batched to minimize network requests.
     * @param event Name of event to be sent.
     * @param integrationType The type of integration used. Should be "custom" for those directly
     * using {@link Braintree} of {@link BraintreeApi} without
     * Drop-In
     */
    public void sendAnalyticsEvent(String event, String integrationType) {
        if (mClientToken.isAnalyticsEnabled()) {
            AnalyticsRequest analyticsRequest = new AnalyticsRequest(mContext, event, integrationType);

            try {
                JSONObject json = new JSONObject(analyticsRequest.toJson());
                json.put("authorizationFingerprint", mClientToken.getAuthorizationFingerprint());

                mHttpRequestFactory.getRequest(HttpMethod.POST, mClientToken.getAnalytics().getUrl())
                        .header("Content-Type", "application/json")
                        .rawBody(json.toString())
                        .execute();
            } catch (JSONException e) {
                // Analytics failures should not interrupt normal application activity
            } catch (UnexpectedException e) {
                // Analytics failures should not interrupt normal application activity
            }
        }
    }

    /**
     * Collect device information for fraud identification purposes.
     *
     * @param activity The currently visible activity.
     * @param environment The Braintree environment to use.
     * @return device_id String to send to Braintree.
     * @see com.braintreepayments.api.data.BraintreeData
     */
    public String collectDeviceData(Activity activity, BraintreeEnvironment environment) {
        return collectDeviceData(activity, environment.getMerchantId(), environment.getCollectorUrl());
    }

    /**
     * Collect device information for fraud identification purposes. This should be used in conjunction
     * with a non-aggregate fraud id.
     *
     * @param activity The currently visible activity.
     * @param merchantId The fraud merchant id from Braintree.
     * @param collectorUrl The fraud collector url from Braintree.
     * @return device_id String to send to Braintree.
     * @see com.braintreepayments.api.data.BraintreeData
     */
    public String collectDeviceData(Activity activity, String merchantId, String collectorUrl) {
        mBraintreeData = new BraintreeData(activity, merchantId, collectorUrl);
        return mBraintreeData.collectDeviceData();
    }

    private String url(String path) {
        return mClientToken.getClientApiUrl() + "/v1/" + path;
    }

    private String pathForPaymentMethodCreate(PaymentMethod.Builder builder) {
        return PAYMENT_METHOD_ENDPOINT + "/" + builder.getApiPath();
    }

    private String jsonForType(String response, String type) throws ServerException {
        JSONObject responseJson = null;
        try {
            responseJson = new JSONObject(response);
            return responseJson.getJSONArray(type)
                    .get(0).toString();
        } catch (JSONException e) {
            throw new ServerException("Parsing server response failed");
        }
    }

    private Map<String, String> getDefaultParameters() {
        Map<String, String> defaults = new HashMap<String, String>();

        defaults.put("authorizationFingerprint", mClientToken.getAuthorizationFingerprint());

        return defaults;
    }

    private HttpRequest GET(String path) {
        HttpRequest requestWrapper = mHttpRequestFactory.getRequest(HttpMethod.GET, url(path));

        for (Map.Entry<String, String> entry : getDefaultParameters().entrySet()) {
            requestWrapper.param(entry.getKey(), entry.getValue());
        }

        return requestWrapper;
    }

    private HttpRequest POST(String path) {
        return mHttpRequestFactory.getRequest(HttpMethod.POST, url(path))
                .header("Content-Type", "application/json");
    }

    private void checkAndThrowErrors(HttpRequest builder)
            throws ErrorWithResponse, BraintreeException {
        switch(builder.statusCode()) {
            case HTTP_OK: case HTTP_CREATED: case HTTP_ACCEPTED:
                return;
            case HTTP_UNAUTHORIZED:
                throw new AuthenticationException();
            case HTTP_FORBIDDEN:
                throw new AuthorizationException();
            case 422: // HTTP_UNPROCESSABLE_ENTITY
                throw new ErrorWithResponse(builder.statusCode(), builder.response());
            case 426: // HTTP_UPGRADE_REQUIRED
                throw new UpgradeRequiredException();
            case HTTP_INTERNAL_ERROR:
                throw new ServerException();
            case HTTP_UNAVAILABLE:
                throw new DownForMaintenanceException();
            default:
                throw new UnexpectedException();
        }
    }
}