package crypto.services;

import crypto.exceptions.APIUnavailableException;
import crypto.mappers.BackloadHistoDataMapper;
import crypto.mappers.TopCoinsMapper;
import crypto.model.historicalModels.Data;
import crypto.model.historicalModels.HistoMinute;
import crypto.model.tablePOJOs.HistoDataDB;
import crypto.model.topCoins.TopCoins;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

/**
 * Created by tanerali on 26/08/2017.
 * Used for backloading raw historical data from CryptoCompare into database
 */
@Service
public class BackloadHistoDataService {

    @Autowired
    BackloadHistoDataMapper backloadHistoDataMapper;

    @Autowired
    CryptoCompareService cryptoCompareService;

    @Autowired
    TopCoinsMapper topCoinsMapper;



    //Taner
    public void backloadPreviouslyMissingHistoData() throws APIUnavailableException {

        //gets a list of the coins in the top_30 coins table
        ArrayList<TopCoins> topCoins = topCoinsMapper.getTopCoins();

        //loops 31 times for the 31 coins with IDs ranging from 1 to 31 except for 20
        for (int i=0; i < topCoins.size(); i++) {

            //the symbol of the coin in the current loop through the list of top coins;
            //used in the call to the CryptoCompare API to specify for which coin data
            //is requested for
            String fsym = topCoins.get(i).getSymbol();

            //the coin ID that corresponds to the fsym parameter;
            //assigning the id of the given coin to variable coinID in order to
            //save the historical data to DB with the id of the coin for which the data
            //is being saved
            int coinID = topCoins.get(i).getEntry_id();

            //variables to hold timestamp of object in current loop going through the array
            //and the next object
            long timeStamp;
            long nextTimeStamp;

            //array receives objects containing timestamps of all entries for given coin with ID coinID
            //from raw_histo_minute table
            HistoDataDB[] minutelyHistoDataDBS = backloadHistoDataMapper.getAllMinutelyTimestamps(coinID);

            //j goes to up to minutelyHistoDataDBS.length-1 because nextTimeStamp would
            //cause an ArrayIndexOutOfBounds if j went up to the last element
            for (int j=0; j < minutelyHistoDataDBS.length-1; j++) {

                timeStamp = minutelyHistoDataDBS[j].getTime();
                nextTimeStamp = minutelyHistoDataDBS[j+1].getTime();

                //if the difference between the timestamps of two consequent entries in the
                //database for a given coin is more than 60 seconds (since this is the raw_histo_minte table),
                //then some data is missing in the period right after timeStamp
                if ( (nextTimeStamp - timeStamp) > 60) {
                    backloadSpecificMinutelyData(fsym, coinID, timeStamp, nextTimeStamp);
                }
            }
        }

    }

    //Taner
    /**
     * Backload missing historical data into one of the 3 historical data tables (histo_minute,
     * histo_hour or histo_day) depending on
     * @param fsym symbol of currency for which the data is fetched
     * @throws APIUnavailableException
     */
    public void backloadSpecificMinutelyData(String fsym, int coinID, long fromMinute, long toMinute)
            throws APIUnavailableException {

        //how many entries to backload into DB determined by the user-specified fromMinute and toMinute params;
        //by finding the difference between the timestamps of the toMinute and fromMinute params and
        //dividing by 60, the number of entries to backload between the 2 timestamps is determined
        //(very useful when used with the backloadPreviouslyMissingHistoData() method which uses
        //this method to fill gaps of missing entries in the DB between 2 timestamps
        int numOfEntriesToBackload = (int) ( (toMinute - fromMinute)/60);

        /*
        if the difference between the toMinute timestamp and the fromMinute timestamp is larger
        than 2000, call backloadSpecificMinutelyData() again recursively to backload the prior
        2000 minutes' worth of data; this backloading in chunks is necessary due to the limit
        imposed by CryptoCompare API whereby we can only get 2000 minutes worth of data at a time
        */
        if (numOfEntriesToBackload > 2000) {
            backloadSpecificMinutelyData(fsym, coinID, fromMinute, toMinute-2000*60);
        }

        /*
        API call to cryptocompare for historical minutely data;
        the toTs parameter to the API call specifies up to (the "to" in toTS) which timestamp
        (the "Ts" in toTS) to return data; the from is specified by the limit parameter to the
        API call for which the maximum is 2000;
        */
        String urlMinutes = "https://min-api.cryptocompare.com/data/histominute?fsym=" + fsym + "&tsym=USD"
                +"&limit="+ numOfEntriesToBackload+ "&toTs="+ toMinute+ "&e=CCCAGG";

        //object that will contain the response from the API call
        HistoMinute histoMinute = new HistoMinute();
        try {
            //API call using Nicola's method to log call and check remaining calls
            histoMinute = (HistoMinute) cryptoCompareService.callCryptoCompareAPI(urlMinutes, histoMinute);

            if (histoMinute.getData().length < 1){
                throw new APIUnavailableException();
            }

        } catch (Exception e){
            throw new APIUnavailableException();
        }

        //variables for calculating percentage change between opening and closing prices of a given coin
        double open;
        double close;
        //% increase = Increase ÷ Original Number × 100.
        //if negative number, then this is a percentage decrease
        double percentChange;

        //will hold all the objects to be uploaded to DB as a batch insert
        ArrayList<HistoDataDB> histoDataDBArrayList = new ArrayList<>();

        //loop that will iterate as many times as there are data objects in the response,
        //assign each data object to a HistoDataDB object and add it to histoDataDBArrayList
        //i starts from 1 because the first element in the array is the one that is at the
        //fromMinute timestamp and it already exists in DB; same reason why i goes to
        //histoMinute.getData().length-1 - because the last element in the array is the
        //one at the toMinute timestamp and it already exists in DB
        for (int i =1; i < histoMinute.getData().length-1; i++) {

            HistoDataDB histoDataDB = new HistoDataDB();

            open = histoMinute.getData()[i].getOpen();
            close = histoMinute.getData()[i].getClose();
            percentChange = ( ( (close - open)/open) * 100);

            histoDataDB.setTime( histoMinute.getData()[i].getTime() );
            histoDataDB.setClose( histoMinute.getData()[i].getClose() );
            histoDataDB.setHigh( histoMinute.getData()[i].getHigh() );
            histoDataDB.setLow( histoMinute.getData()[i].getLow() );
            histoDataDB.setOpen( histoMinute.getData()[i].getOpen() );
            histoDataDB.setVolumefrom( histoMinute.getData()[i].getVolumefrom() );
            histoDataDB.setVolumeto( histoMinute.getData()[i].getVolumeto() );
            histoDataDB.setCoin_id( coinID );
            histoDataDB.setPercent_change(percentChange);

            histoDataDBArrayList.add(histoDataDB);
        }

        backloadHistoDataMapper.insertHistoMinuteData(histoDataDBArrayList);

    }





    //Taner
    //backloading from the last entry in the database up to the current time
    public void backloadRecentHistoData() throws APIUnavailableException {

        //gets a list of the coins in the top_30 coins table
        ArrayList<TopCoins> topCoins = topCoinsMapper.getTopCoins();


        //gets the coin symbols from the top 30 coins table and backloads the histo
        //data for each coin in each table
        for (int i = 0; i < topCoins.size(); i++) {

            //the symbol of the coin in the current loop through the list of top coins;
            //used in the call to the CryptoCompare API to specify for which coin data
            //is requested for
            String fsym = topCoins.get(i).getSymbol();

            //the coin ID that corresponds to the fsym parameter;
            //assigning the id of the given coin to variable coinID in order to
            //save the historical data to DB with the id of the coin for which the data
            //is being saved
            int coinID = topCoins.get(i).getEntry_id();




            //----------------MINUTES----------------

            String urlMinutes = "https://min-api.cryptocompare.com/data/histominute?fsym=" + fsym + "&tsym=USD"
                    + "&limit=2000&e=CCCAGG";

            //object that will contain the response from the API call
            HistoMinute histoMinute = new HistoMinute();
            try {
                //API call using Nicola's method to log call and check remaining calls
                histoMinute = (HistoMinute) cryptoCompareService.callCryptoCompareAPI(urlMinutes, histoMinute);

                if (histoMinute.getData().length < 1) {
                    throw new APIUnavailableException();
                }

            } catch (Exception e) {
                throw new APIUnavailableException();
            }

            //holds the timestamp of the latest entry in the histo_minute table
            long lastHistominTime;

            //used as index in Data array in the response from the API call (the histoMinute object)
            int indexMinute = 0;

            //tries to retrieve timestamp of last entry in DB for the given coin;
            //if no entry exists for given coin, the catch statement executes
            try {
                lastHistominTime = backloadHistoDataMapper.getLastHistominEntry(coinID).getTime();

                //used to determine from which element in the Data array from the API response
                //backloading should start; this is to avoid backloading duplicate data
                for (Data meetingPoint : histoMinute.getData()) {

                    //each time through the loop indexMinute is incremented; when a match is found between
                    //the timestamp of the last entry in the DB for the given coin and a timestamp in the
                    //API response for the given coin, the loop breaks; now, when indexMinute is used as the index
                    //when looping through the array from the API response, the first element retrieved from
                    //the API response to be backloaded into the DB will be the element at indexMinute, hence
                    //it will be the element that should be right after the last entry in the DB
                    indexMinute++;
                    if (meetingPoint.getTime() == lastHistominTime) {
                        break;
                    }

                /*
                since only 2001 elements can be received from the API response at a time, if indexMinute
                reaches 2001, it is reset back to 0 since no entry in the DB was found that matched
                any of the elements in the response from the API call; now that it is reset back to 0,
                all the elements from the response will be backloaded since not finding a match either means
                that the method has not been run for so long that the API data has advanced too
                much in time or that simply no entries have been uploaded to the DB for that given coin;
                also, the lastHistoMinTime is assigned timestamp of first element in array
                from API response so that backloading starts from the first element in the array
                */
                    if (indexMinute == 2001) {
                        indexMinute = 0;
                        lastHistominTime = histoMinute.getData()[0].getTime();
                        break;
                    }

                }

                //if no entry exists for given coin, timestamp of first element in array
                //from API response assigned to lastHistoMinTime so that backloading starts
                //from the first element in the array, thus backloading all the data received from
                //the response since no data exists for given coin
            } catch (Exception e) {
                lastHistominTime = histoMinute.getData()[0].getTime();
            }


            //variables for calculating percentage change between opening and closing prices of a given coin
            double open;
            double close;
            //% increase = Increase ÷ Original Number × 100.
            //if negative number, then this is a percentage decrease
            double percentChange;

            //will hold all the objects to be uploaded to DB as a batch insert
            ArrayList<HistoDataDB> histoDataDBArrayList = new ArrayList<>();

            //loop that will iterate as many times as there are data objects in the response,
            //assign each data object to a HistoDataDB object and add it to histoDataDBArrayList
            for (long j = lastHistominTime;
                 j < histoMinute.getData()[histoMinute.getData().length - 1].getTime(); j = j + 60) {

                HistoDataDB histoDataDB = new HistoDataDB();

//                  can be used to convert time in seconds from API call to specific date and time
//                  histoDataDB.setTime( DateUnix.secondsToSpecificTime( histoMinute.getData()[i].getTime() ) );

                open = histoMinute.getData()[indexMinute].getOpen();
                close = histoMinute.getData()[indexMinute].getClose();
                percentChange = (((close - open) / open) * 100);

                histoDataDB.setTime(histoMinute.getData()[indexMinute].getTime());
                histoDataDB.setClose(histoMinute.getData()[indexMinute].getClose());
                histoDataDB.setHigh(histoMinute.getData()[indexMinute].getHigh());
                histoDataDB.setLow(histoMinute.getData()[indexMinute].getLow());
                histoDataDB.setOpen(histoMinute.getData()[indexMinute].getOpen());
                histoDataDB.setVolumefrom(histoMinute.getData()[indexMinute].getVolumefrom());
                histoDataDB.setVolumeto(histoMinute.getData()[indexMinute].getVolumeto());
                histoDataDB.setCoin_id(coinID);
                histoDataDB.setPercent_change(percentChange);

//                backloadHistoDataMapper.insertHistoMinuteIntoDB(histoDataDB);

                histoDataDBArrayList.add(histoDataDB);

                indexMinute++;

            }

            backloadHistoDataMapper.insertHistoMinuteData(histoDataDBArrayList);

        }
    }







//    //Taner
//    //NO LONGER USED
//    //calls all 3 methods for backloading historical data into all 3
//    //raw historical data tables;
//    //it does this for all top 30 coins by getting the coin symbols from
//    //the top 30 coins table
//    public void backloadHistoricalData (String tsym, String exchange)
//            throws APIUnavailableException {
//
//        //gets the coin symbols from the top 30 coins table and backloads the histo
//        //data for each coin in each table
//        for (int i = 0; i < topCoinsMapper.getTopCoins().size(); i++) {
//
//            String fsym = topCoinsMapper.getTopCoins().get(i).getSymbol();
//
//            saveMinutelyHistoricalDataToDB(fsym, tsym, exchange);
//        }
//    }
//
//
//    //Taner
//    //used for backloading minutely historical data to DB
//    //no longer necessary, above method has this functionality;
//    //kept for experimenting with batch insert attempts
//    public void saveMinutelyHistoricalDataToDB (String fsym, String tsym, String exchange)
//            throws APIUnavailableException {
//
//        //API call to cryptocompare for historical minutely data
//        String url = "https://min-api.cryptocompare.com/data/histominute?fsym=" + fsym + "&tsym=" + tsym
//                +"&limit=2000&e="+exchange;
//
//        //object that will receive the response from the API call
//        HistoMinute historical = new HistoMinute();
//        try {
//            //API call being assigned to HistoMinute object
//            historical = (HistoMinute) cryptoCompareService.callCryptoCompareAPI(url, historical);
//
//            if (historical.getData().length < 1){
//                throw new APIUnavailableException();
//            }
//
//        } catch (Exception e){
//            throw new APIUnavailableException();
//        }
//
//        //using MyBatis to retrieve the coin that corresponds to the fsym parameter;
//        //assigning the id of the given coin to variable coin_id in order to
//        //save the historical data to DB with the id of the coin for which the data
//        //is being saved
//        int coin_id = topCoinsMapper.findBySymbol(fsym).getEntry_id();
//
//        //loop that will iterate as many times as there are data objects in the response,
//        //assign each data object to a HistoDataDB object and then upload that HistoDataDB
//        //object to DB
//        for (int i =0; i < historical.getData().length; i++) {
//
//            HistoDataDB histoDataDB = new HistoDataDB();
//
//            histoDataDB.setTime( historical.getData()[i].getTime() );
//            histoDataDB.setClose( historical.getData()[i].getClose() );
//            histoDataDB.setHigh( historical.getData()[i].getHigh() );
//            histoDataDB.setLow( historical.getData()[i].getLow() );
//            histoDataDB.setOpen( historical.getData()[i].getOpen() );
//            histoDataDB.setVolumefrom( historical.getData()[i].getVolumefrom() );
//            histoDataDB.setVolumeto( historical.getData()[i].getVolumeto() );
//            histoDataDB.setCoin_id( coin_id );
//
//
//            backloadHistoDataMapper.insertHistoMinuteIntoDB(histoDataDB);
//        }

}
