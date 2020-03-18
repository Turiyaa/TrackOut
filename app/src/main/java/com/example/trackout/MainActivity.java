package com.example.trackout;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.gson.Gson;
import com.movesense.mds.Mds;
import com.movesense.mds.MdsConnectionListener;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsNotificationListener;
import com.movesense.mds.MdsSubscription;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.scan.ScanSettings;

import org.apache.commons.math3.filter.DefaultMeasurementModel;
import org.apache.commons.math3.filter.KalmanFilter;
import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;

import java.sql.Time;
import java.util.ArrayList;
import java.util.List;

import rx.Subscription;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemLongClickListener, AdapterView.OnItemClickListener{


    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    private static final int MY_PERMISSIONS_REQUEST_LOCATION = 1;

    //For previous class
    ArrayList list = new ArrayList();
    ArrayList<AccDataResponse> dataResponses = new ArrayList<AccDataResponse>();
    public static ArrayList<AccDataResponse> fiftySampleData = new ArrayList<AccDataResponse>();

    //Action bar
    //MDS
    private Mds mMds;
    public static final String URI_CONNECTEDDEVICES = "suunto://MDS/ConnectedDevices";
    public static final String URI_EVENTLISTENER = "suunto://MDS/EventListener";
    public static final String SCHEME_PREFIX = "suunto://";

    //BleClient singleton
    static private RxBleClient mBleClient;

    // UI
    private ListView mScanResultListView;
    private ArrayList<MyScanResult> mScanResArrayList = new ArrayList<>();
    ArrayAdapter<MyScanResult> mScanResArrayAdapter;

    // Sensor subscription
    static private String URI_MEAS_ACC_13 = "/Meas/Acc/13";
    private MdsSubscription mdsSubscription;
    private String subscribedDeviceSerial;


    // Display Steps
    View view;
    int stepCounter;
    private Handler handler = new Handler();
    int progressStatus = 0;

    //Connection Activity
    private boolean isConnected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Init Scan UI
        mScanResultListView = (ListView)findViewById(R.id.listScanResult);
        mScanResArrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, mScanResArrayList);
        mScanResultListView.setAdapter(mScanResArrayAdapter);
        mScanResultListView.setOnItemLongClickListener(this);
        mScanResultListView.setOnItemClickListener(this);

        // Make sure we have all the permissions this app needs
        requestNeededPermissions();

        // Initialize Movesense MDS library
        initMds();

        ProgressBar progressBar = (ProgressBar) findViewById(R.id.progressBar2);
        new Thread(new Runnable() {
            public void run() {
                while (progressStatus < 200) {

                    // Update the progress bar and display the
                    //current value in the text view
                    if (!fiftySampleData.isEmpty()) {
                        // calculate steps

                        progressStatus += calculateSteps();
                        fiftySampleData.clear();
                    }
                    String stepStr = progressStatus + "";
                    handler.post(new Runnable() {
                        public void run() {
                            progressBar.setProgress(progressStatus);
                            ((TextView) findViewById(R.id.stepCounter)).setText(stepStr);
                        }
                    });
                    try {
                        // Sleep for 200 milliseconds.
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }
    private RxBleClient getBleClient() {
        // Init RxAndroidBle (Ble helper library) if not yet initialized
        if (mBleClient == null)
        {
            mBleClient = RxBleClient.create(this);
        }

        return mBleClient;
    }

    private void initMds() {
        mMds = Mds.builder().build(this);
    }

    void requestNeededPermissions()
    {
        // Here, thisActivity is the current activity
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            // No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    MY_PERMISSIONS_REQUEST_LOCATION);

        }
    }

    Subscription mScanSubscription;
    public void onScanClicked(View view) {
        findViewById(R.id.buttonScan).setVisibility(View.GONE);
        findViewById(R.id.buttonScanStop).setVisibility(View.VISIBLE);

        // Start with empty list
        mScanResArrayList.clear();
        mScanResArrayAdapter.notifyDataSetChanged();

        mScanSubscription = getBleClient().scanBleDevices(
                new ScanSettings.Builder()
                        // .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // change if needed
                        // .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // change if needed
                        .build()
                // add filters if needed
        )
                .subscribe(
                        scanResult -> {
                            Log.d(LOG_TAG,"scanResult: " + scanResult);

                            // Process scan result here. filter movesense devices.
                            if (scanResult.getBleDevice()!=null &&
                                    scanResult.getBleDevice().getName() != null &&
                                    scanResult.getBleDevice().getName().startsWith("Movesense")) {

                                // replace if exists already, add otherwise
                                MyScanResult msr = new MyScanResult(scanResult);
                                if (mScanResArrayList.contains(msr))
                                    mScanResArrayList.set(mScanResArrayList.indexOf(msr), msr);
                                else
                                    mScanResArrayList.add(0, msr);

                                mScanResArrayAdapter.notifyDataSetChanged();
                            }
                        },
                        throwable -> {
                            Log.e(LOG_TAG,"scan error: " + throwable);
                            // Handle an error here.

                            // Re-enable scan buttons, just like with ScanStop
                            onScanStopClicked(null);
                        }
                );
    }

    public void onScanStopClicked(View view) {
        if (mScanSubscription != null)
        {
            mScanSubscription.unsubscribe();
            mScanSubscription = null;
        }

        findViewById(R.id.buttonScan).setVisibility(View.VISIBLE);
        findViewById(R.id.buttonScanStop).setVisibility(View.GONE);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= mScanResArrayList.size())
            return;

        MyScanResult device = mScanResArrayList.get(position);
        if (!device.isConnected()) {
            // Stop scanning
            onScanStopClicked(null);

            // And connect to the device
            connectBLEDevice(device);
        }
        else {
            // Device is connected, trigger showing /Info
            subscribeToSensor(device.connectedSerial);
        }
    }

    private void subscribeToSensor(String connectedSerial) {
        // Clean up existing subscription (if there is one)
        if (mdsSubscription != null) {
            unsubscribe();
        }

        // Build JSON doc that describes what resource and device to subscribe
        // Here we subscribe to 13 hertz accelerometer data
        StringBuilder sb = new StringBuilder();
        String strContract = sb.append("{\"Uri\": \"").append(connectedSerial).append(URI_MEAS_ACC_13).append("\"}").toString();
        Log.d(LOG_TAG, strContract);
        final View sensorUI = findViewById(R.id.sensorUI);
        final Button buttonUnsubscribe = findViewById(R.id.buttonUnsubscribe);

        subscribedDeviceSerial = connectedSerial;

        mdsSubscription = mMds.builder().build(this).subscribe(URI_EVENTLISTENER,
                strContract, new MdsNotificationListener() {
                    @Override
                    public void onNotification(String data) {
                        Log.d(LOG_TAG, "onNotification(): " + data);

                        // If UI not enabled, do it now
                        if (sensorUI.getVisibility() == View.GONE && buttonUnsubscribe.getVisibility() == View.GONE){

                            sensorUI.setVisibility(View.VISIBLE);
                            buttonUnsubscribe.setVisibility(View.VISIBLE);
                        }

                        AccDataResponse accResponse = new Gson().fromJson(data, AccDataResponse.class);
                        if (accResponse != null && accResponse.body.array.length > 0) {
                            String accStr =
                                    String.format("%.02f, %.02f, %.02f", accResponse.body.array[0].x, accResponse.body.array[0].y, accResponse.body.array[0].z);
                            list.add(accStr.concat(","+ accResponse.body.timestamp));
                            dataResponses.add(accResponse);
                            if(dataResponses.size() % 5 == 0){
                                // Process 50 sammple data to calculate steps
                                fiftySampleData.addAll(dataResponses);
                                dataResponses.clear();
                            }
                            ((TextView)findViewById(R.id.sensorMsg)).setText(accStr);
                        }
                    }

                    @Override
                    public void onError(MdsException error) {
                        Log.e(LOG_TAG, "subscription onError(): ", error);
                        unsubscribe();
                    }
                });

    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= mScanResArrayList.size())
            return false;

        MyScanResult device = mScanResArrayList.get(position);

        // unsubscribe if there
        Log.d(LOG_TAG, "onItemLongClick, " + device.connectedSerial + " vs " + subscribedDeviceSerial);
        if (device.connectedSerial.equals(subscribedDeviceSerial))
            unsubscribe();

        Log.i(LOG_TAG, "Disconnecting from BLE device: " + device.macAddress);
        mMds.disconnect(device.macAddress);

        return true;
    }

    private void connectBLEDevice(MyScanResult device) {
        RxBleDevice bleDevice = getBleClient().getBleDevice(device.macAddress);

        Log.i(LOG_TAG, "Connecting to BLE device: " + bleDevice.getMacAddress());
        mMds.connect(bleDevice.getMacAddress(), new MdsConnectionListener() {

            @Override
            public void onConnect(String s) {
                Log.d(LOG_TAG, "onConnect:" + s);
            }

            @Override
            public void onConnectionComplete(String macAddress, String serial) {
                for (MyScanResult sr : mScanResArrayList) {
                    if (sr.macAddress.equalsIgnoreCase(macAddress)) {
                        sr.markConnected(serial);
                        break;
                    }
                }
                mScanResArrayAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "onError:" + e);

                showConnectionError(e);
            }

            @Override
            public void onDisconnect(String bleAddress) {

                Log.d(LOG_TAG, "onDisconnect: " + bleAddress);
                for (MyScanResult sr : mScanResArrayList) {
                    if (bleAddress.equals(sr.macAddress))
                    {
                        // unsubscribe if was subscribed
                        if (sr.connectedSerial != null && sr.connectedSerial.equals(subscribedDeviceSerial))
                            unsubscribe();

                        sr.markDisconnected();
                    }
                }
                mScanResArrayAdapter.notifyDataSetChanged();
            }
        });
    }

    private void showConnectionError(MdsException e) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Connection Error:")
                .setMessage(e.getMessage());

        builder.create().show();
    }

    private void unsubscribe() {
        if (mdsSubscription != null) {
            mdsSubscription.unsubscribe();
            mdsSubscription = null;
        }

        subscribedDeviceSerial = null;

        // If UI not invisible, do it now
        final View sensorUI = findViewById(R.id.sensorUI);
        final Button buttonUnsubscribe = findViewById(R.id.buttonUnsubscribe);
        if (sensorUI.getVisibility() != View.GONE && buttonUnsubscribe.getVisibility() != View.GONE){

            sensorUI.setVisibility(View.GONE);
            buttonUnsubscribe.setVisibility(View.GONE);
        }
        int size = list.size();
        for (int i = 0; i<size; i++){
            Log.i("XYZ",""+list.get(i));
        }
    }
    public void onUnsubscribeClicked(View view) {
        unsubscribe();
    }

    public int calculateSteps(){
        int stepCounter = 0;
        double magnitude;
        double sum = 0;
        double mean = 0;
        double std = 0;
        Double meanPeakHeight;
        List<Double> magNogList = new ArrayList<Double>();
        List<Double> magList = new ArrayList<Double>();
        for(AccDataResponse dtr: fiftySampleData){
            Double xAxis = dtr.body.array[0].x;
            Double yAxis = dtr.body.array[0].y;
            Double zAxis = dtr.body.array[0].z;
            magnitude = Math.sqrt(Math.pow(xAxis,2)+Math.pow(yAxis,2)+Math.pow(zAxis,2));
            sum += magnitude;
            magList.add(magnitude);
        }
        mean = sum/fiftySampleData.size();
        for(Double mag: magList) {
            Double magNog = mag - mean;
            magNogList.add(magNog);
        }
        StandardDeviation stds = new StandardDeviation();
        //double[] arr = magNogList.stream().mapToDouble(Double::doubleValue).toArray();
        double[] arr = new double[magNogList.size()];
        for(int i = 0; i<magNogList.size(); i++){
            arr[i] = magNogList.get(i);
        }
        std = stds.evaluate(arr);
        Log.i(LOG_TAG, "**********************************************************************************\n" +
                "***********************************************************************************************\n" +
                "***********************************************************************************************"+ std);

        // this is calculated threshold of Narayan- Calculated using matlab
        // we need to find this dynamically
        if(std < 2.238){
            std = 2.238;
        }

        // find local maxima
        List localMaximaList = localMaxima(std, arr);
        for(Object obj: localMaximaList){
            Double step;
            step = Double.parseDouble(obj.toString());
            if(step> std){
                stepCounter++;
            }
        }
        return stepCounter;
    }
    public List localMaxima(double std, double[] array){
        List<Double> list  = new ArrayList<>();
        for(int i = 1; i<array.length-1; i++){
            int previous = i-1;
            int present = i;
            int next = i + 1;
                if(array[present] > array[previous]){
                    if(array[present]> array[next]){

                        list.add(array[present]);
                        Log.i(LOG_TAG, "*****************Maxima" + array[present]);
                    }
            }
        }
        return list;
    }

}
