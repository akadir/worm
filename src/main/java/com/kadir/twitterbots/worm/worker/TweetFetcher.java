package com.kadir.twitterbots.worm.worker;

import com.kadir.twitterbots.authentication.BotAuthenticator;
import com.kadir.twitterbots.ratelimithandler.handler.RateLimitHandler;
import com.kadir.twitterbots.ratelimithandler.process.ApiProcessType;
import com.kadir.twitterbots.worm.dao.StatusDao;
import com.kadir.twitterbots.worm.entity.CustomStatus;
import com.kadir.twitterbots.worm.entity.TaskPriority;
import com.kadir.twitterbots.worm.exceptions.IllegalLanguageKeyException;
import com.kadir.twitterbots.worm.filter.InteractionCountFilter;
import com.kadir.twitterbots.worm.scheduler.BaseScheduledRunnable;
import com.kadir.twitterbots.worm.scheduler.TaskScheduler;
import com.kadir.twitterbots.worm.util.DataUtil;
import com.kadir.twitterbots.worm.util.StatusUtil;
import com.kadir.twitterbots.worm.util.WormConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import twitter4j.*;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author akadir
 * Date: 08/12/2018
 * Time: 15:10
 */
public class TweetFetcher extends BaseScheduledRunnable {
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private final Map<Long, CustomStatus> fetchedStatusMap = new HashMap<>();
    private boolean isCancelled = false;

    private String languageKey;
    private int statusLimitToKeep;
    private Twitter twitter;
    private final TweetFilter tweetFilter;
    private final StatusDao statusDao;
    private static final int INITIAL_DELAY = 0;
    private static final int DELAY = 1;

    public TweetFetcher() {
        super(TaskPriority.LOW);
        loadArguments();
        authenticate();

        tweetFilter = new TweetFilter();
        tweetFilter.initForFetch(twitter);

        DatabaseWorker databaseWorker = new DatabaseWorker(this);
        databaseWorker.schedule();

        statusDao = new StatusDao();
        addTodaysStatusesIntoMap();
        executorService = Executors.newScheduledThreadPool(1);
    }

    public void schedule() {
        scheduledFuture = executorService.scheduleWithFixedDelay(this, INITIAL_DELAY, DELAY, TimeUnit.MINUTES);
        logger.info("add scheduler to run with fixed delay. initial delay:{} delay:{}", INITIAL_DELAY, DELAY);
        TaskScheduler.addScheduledTask(this);
    }

    public void run() {
        try {
            fetchTweets();
        } catch (TwitterException e) {
            logger.error("Error while fetching tweets.", e);
        } catch (Exception e) {
            logger.error("An error occured!", e);
        }
    }

    @Override
    public void cancel() {
        isCancelled = true;
        super.cancel();
    }

    private void authenticate() {
        twitter = BotAuthenticator.authenticate(WormConstants.AUTH_PROPERTIES_FILE_NAME, WormConstants.FETCH_API_KEYS_PREFIX);
    }

    private void fetchTweets() throws TwitterException {
        List<Status> statuses;
        Query query = new Query("lang:" + languageKey);
        query.setCount(100);
        query.setResultType(Query.RECENT);
        query.since(simpleDateFormat.format(new Date()));

        do {
            QueryResult result = twitter.search(query);
            statuses = result.getTweets();
            logger.info("Fetch {} statuses. Completed in: {}", statuses.size(), result.getCompletedIn());

            for (Status status : statuses) {
                checkStatus(status);
            }

            query = result.nextQuery();
            RateLimitHandler.handle(twitter.getId(), result.getRateLimitStatus(), ApiProcessType.SEARCH);
        } while (query != null && !isCancelled);
    }

    private void checkStatus(Status status) {
        if (status.isRetweet()) {
            status = status.getRetweetedStatus();

            if (tweetFilter.canStatusBeUsed(status)) {
                addStatus(status);
            }
        }

        if (status.getQuotedStatus() != null) {
            status = status.getQuotedStatus();

            if (tweetFilter.canStatusBeUsed(status)) {
                addStatus(status);
            }
        }
    }

    private void addStatus(Status newFetchedStatus) {
        CustomStatus customStatus = fetchedStatusMap.get(newFetchedStatus.getId());
        if (customStatus != null) {
            if (customStatus.getScore() != StatusUtil.calculateInteractionCount(newFetchedStatus)) {
                customStatus.setScore(StatusUtil.calculateInteractionCount(newFetchedStatus));
                logger.info("Update status score in map. {} - {}", customStatus.getScore(), customStatus.getStatusLink());
            }
        } else {
            CustomStatus alreadyMappedStatus = getAnotherStatusOfUserIfExist(newFetchedStatus);
            if (alreadyMappedStatus != null) {
                if (alreadyMappedStatus.getScore() < StatusUtil.calculateInteractionCount(newFetchedStatus)) {
                    replaceUserStatusByStatusScore(alreadyMappedStatus, newFetchedStatus);
                }
            } else {
                customStatus = new CustomStatus(newFetchedStatus);
                fetchedStatusMap.put(newFetchedStatus.getId(), new CustomStatus(newFetchedStatus));
                logger.info("Save status into map. {} - {}", customStatus.getScore(), customStatus.getStatusLink());
            }
        }

        if (fetchedStatusMap.size() > statusLimitToKeep) {
            removeStatusesWithLowestInteractionFromMap();
        }
    }

    private void replaceUserStatusByStatusScore(CustomStatus alreadyMappedStatus, Status status) {
        CustomStatus newFetchedStatus = new CustomStatus(status);
        fetchedStatusMap.remove(alreadyMappedStatus.getStatusId());
        fetchedStatusMap.put(status.getId(), newFetchedStatus);
        logger.info("Replace user status. {} - {}", newFetchedStatus.getScore(), newFetchedStatus.getStatusLink());
    }

    private CustomStatus getAnotherStatusOfUserIfExist(Status status) {
        List<CustomStatus> userStatusList = fetchedStatusMap.values().stream()
                .filter(value -> value.getUserId() == status.getUser().getId())
                .collect(Collectors.toList());

        return !userStatusList.isEmpty() ? userStatusList.get(0) : null;
    }

    private void removeStatusesWithLowestInteractionFromMap() {
        List<CustomStatus> customStatusList = new ArrayList<>(fetchedStatusMap.values());

        removeDeletedStatuses(customStatusList);

        if (fetchedStatusMap.size() > statusLimitToKeep) {
            customStatusList.sort(Comparator.comparing(CustomStatus::getScore).reversed());
            for (int i = statusLimitToKeep; i < customStatusList.size(); i++) {
                CustomStatus customStatus = fetchedStatusMap.remove(customStatusList.get(i).getStatusId());
                if (customStatus != null) {
                    logger.info("Remove status from map: {} - {}", customStatus.getScore(), customStatus.getStatusLink());
                }
            }

            setMinInteractionCount();
        }
    }

    private void setMinInteractionCount() {
        int tempMinScore = -1;
        for (CustomStatus status : fetchedStatusMap.values()) {
            if (tempMinScore == -1 || status.getScore() < tempMinScore) {
                tempMinScore = status.getScore();
            }
        }
        InteractionCountFilter.setMinInteractionCount(tempMinScore);
        logger.info("Set minInteractionCount:{}", InteractionCountFilter.getMinInteractionCount());
    }

    private void removeDeletedStatuses(List<CustomStatus> customStatusList) {
        Iterator<CustomStatus> iterator = customStatusList.iterator();
        LocalDateTime now = LocalDateTime.now();

        while (iterator.hasNext()) {
            CustomStatus customStatus = iterator.next();
            if (ChronoUnit.MINUTES.between(customStatus.getFetchedAt(), now) > WormConstants.CHECK_DELETED_STATUSES_PERIOD) {
                try {
                    Status s = twitter.showStatus(customStatus.getStatusId());
                    customStatus = new CustomStatus(s);
                    fetchedStatusMap.put(customStatus.getStatusId(), customStatus);
                    RateLimitHandler.handle(twitter.getId(), s.getRateLimitStatus(), ApiProcessType.SHOW_STATUS);
                } catch (TwitterException e) {
                    if (e.getErrorCode() == 144) {
                        fetchedStatusMap.remove(customStatus.getStatusId());
                        iterator.remove();
                    }
                    logger.error("Error occured while getting status information from status.", e);
                }
            }
        }
    }

    private void addTodaysStatusesIntoMap() {
        List<CustomStatus> todaysStatuses = statusDao.getTodaysStatuses();

        for (CustomStatus customStatus : todaysStatuses) {
            fetchedStatusMap.put(customStatus.getStatusId(), customStatus);
            logger.debug("Load status from database. {} - {}", customStatus.getScore(), customStatus.getStatusLink());
        }

        if (fetchedStatusMap.size() > 0) {
            logger.info("load status from database: {}", fetchedStatusMap.size());
            setMinInteractionCount();
        }
    }

    private void loadArguments() {
        this.languageKey = System.getProperty("languageKey");
        this.statusLimitToKeep = Integer.parseInt(System.getProperty("statusLimitToKeep", "30"));
        if (DataUtil.isNullOrEmpty(languageKey)) {
            throw new IllegalLanguageKeyException(languageKey);
        } else {
            logger.debug("Set languageKey:{}", languageKey);
        }
    }

    public Map<Long, CustomStatus> getFetchedStatusMap() {
        return fetchedStatusMap;
    }
}