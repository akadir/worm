package com.kadir.twitterbots.populartweetfinder.authentication;

import com.kadir.twitterbots.populartweetfinder.exceptions.TwitterAuthenticationException;
import com.kadir.twitterbots.populartweetfinder.util.DataUtil;
import org.apache.log4j.Logger;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.TwitterFactory;
import twitter4j.User;
import twitter4j.auth.AccessToken;
import twitter4j.auth.RequestToken;
import twitter4j.conf.ConfigurationBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * @author akadir
 * Date: 08/12/2018
 * Time: 13:35
 */
public class BotAuthentication {
    private final Logger logger = Logger.getLogger(this.getClass());

    private String consumerKey;
    private String consumerSecret;
    private String accessToken;
    private String accessTokenSecret;

    public BotAuthentication(String consumerKey, String consumerSecret, String accessToken, String accessTokenSecret) {
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.accessToken = accessToken;
        this.accessTokenSecret = accessTokenSecret;
    }

    public Twitter authenticate() {
        Twitter twitter = null;
        try {
            validateTokens();
            ConfigurationBuilder cb = buildConfigurationBuilder();

            twitter = new TwitterFactory(cb.build()).getInstance();
            User authUser = twitter.showUser(twitter.verifyCredentials().getId());
            logger.info("Bot authenticated successfully. Account name: " + authUser.getName() + " - " + authUser.getScreenName() + " - " + authUser.getId());
        } catch (IOException | TwitterException e) {
            logger.error("An error occured while authenticating twitter account.", e);
            throw new TwitterAuthenticationException(e);
        }

        return twitter;
    }

    private ConfigurationBuilder buildConfigurationBuilder() {
        return new ConfigurationBuilder()
                .setOAuthConsumerKey(consumerKey)
                .setOAuthConsumerSecret(consumerSecret)
                .setOAuthAccessToken(accessToken)
                .setOAuthAccessTokenSecret(accessTokenSecret);
    }

    private void validateTokens() throws TwitterException, IOException {
        if (DataUtil.isNullOrEmpty(consumerKey) || DataUtil.isNullOrEmpty(consumerSecret)) {
            throw new IllegalArgumentException("consumerKey or consumerSecret cannot be null or empty.");
        } else if (DataUtil.isNullOrEmpty(accessToken) || DataUtil.isNullOrEmpty(accessTokenSecret)) {
            getAccessTokenAndSecretFromTwitter();
        }
    }

    private void getAccessTokenAndSecretFromTwitter() throws TwitterException, IOException {
        Twitter twitter = TwitterFactory.getSingleton();
        twitter.setOAuthConsumer(consumerKey, consumerSecret);

        RequestToken requestToken = twitter.getOAuthRequestToken();
        AccessToken authAccessToken = null;

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        while (null == authAccessToken) {
            logger.info("Open the following URL and grant access to your account:");
            logger.info(requestToken.getAuthorizationURL());
            logger.info("Enter the PIN (if available) or just hit enter.[PIN]:");
            String pin;
            pin = br.readLine();
            if (pin.length() > 0) {
                authAccessToken = twitter.getOAuthAccessToken(requestToken, pin);
                accessToken = authAccessToken.getToken();
                accessTokenSecret = authAccessToken.getTokenSecret();
            } else {
                authAccessToken = twitter.getOAuthAccessToken();
            }
        }
    }
}
