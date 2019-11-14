/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2019 Botts Innovative Research, Inc. All Rights Reserved.
 ******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.driver.spotreport;

import java.io.ByteArrayOutputStream;
import java.util.List;

import net.opengis.swe.v20.BinaryComponent;
import net.opengis.swe.v20.BinaryEncoding;
import net.opengis.swe.v20.Boolean;
import net.opengis.swe.v20.ByteEncoding;
import net.opengis.swe.v20.ByteOrder;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.DataType;
import net.opengis.swe.v20.Text;
import net.opengis.swe.v20.Vector;

import org.sensorhub.api.sensor.SensorDataEvent;
import org.sensorhub.api.sensor.SensorException;
import org.sensorhub.impl.sensor.AbstractSensorOutput;
import org.sensorhub.impl.sensor.videocam.VideoCamHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vast.cdm.common.CDMException;
import org.vast.data.DataBlockMixed;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import android.util.Log;

/**
 * <p>
 * Implementation of data interface for Spot Reports
 * </p>
 *
 * @author Nicolas Garay <nicolasgaray@icloud.com>
 * @since Nov 9, 2019
 */
public class SpotReportOutput extends AbstractSensorOutput<SpotReportDriver> {

    // keep logger name short because in LogCat it's max 23 chars
    static final Logger log = LoggerFactory.getLogger(SpotReportOutput.class.getSimpleName());

    // Data Associated with Broadcast Receivers and Intents
    private static final String ACTION_SUBMIT_REPORT = "org.sensorhub.android.intent.SPOT_REPORT";
    private static final int SUBMIT_REPORT_FAILURE = 0;
    private static final int SUBMIT_REPORT_SUCCESS = 1;
    private static final String DATA_LOC = "location";
    private static final String DATA_REPORT_NAME = "name";
    private static final String DATA_REPORT_DESCRIPTION = "description";
    private static final String DATA_REPORT_CATEGORY = "item";
    private static final String DATA_REPORT_IMAGE = "image";
    private SpotReportReceiver broadcastReceiver = new SpotReportReceiver();

    // SWE DataBlock elements
    private static final String DATA_RECORD_LOC_LABEL = "location";
    private static final String DATA_RECORD_REPORT_NAME_LABEL = "name";
    private static final String DATA_RECORD_REPORT_DESCRIPTION_LABEL = "description";
    private static final String DATA_RECORD_REPORTING_CATEGORY_LABEL = "category";
    private static final String DATA_RECORD_REPORTING_CONTAINS_IMAGE_LABEL = "has-image";
    private static final String DATA_RECORD_REPORTING_IMAGE_LABEL = "image";

    private static final String DATA_RECORD_NAME = "Spot Report";
    private static final String DATA_RECORD_DESCRIPTION =
            "A report generated by visual observance and classification which is accompanied by a" +
                    " location, description, and optionally an image";
    private static final String DATA_RECORD_DEFINITION =
            SWEHelper.getPropertyUri("SpotReport");
    private int imgHeight;
    private int imgWidth;
    private DataComponent dataStruct;
    private DataEncoding dataEncoding;

    private Context context;
    private String name;

    private ByteArrayOutputStream imageBuffer = new ByteArrayOutputStream();

    protected SpotReportOutput(SpotReportDriver parentModule) {

        super(parentModule);
        this.name = "spot_report_data";

        this.imgWidth = parentModule.getConfiguration().imgWidth;
        this.imgHeight = parentModule.getConfiguration().imgHeight;
    }

    @Override
    public String getName() {

        return name;
    }

    protected void init() throws SensorException {

        SWEHelper sweHelper = new SWEHelper();
        dataStruct = sweHelper.newDataRecord(7);
        dataStruct.setDescription(DATA_RECORD_DESCRIPTION);
        dataStruct.setDefinition(DATA_RECORD_DEFINITION);
        dataStruct.setName(DATA_RECORD_NAME);

        // Add the location component of the data record
        GeoPosHelper geoPosHelper = new GeoPosHelper();
        Vector locationVectorLLA = geoPosHelper.newLocationVectorLLA(null);
        locationVectorLLA.setLocalFrame(parentSensor.localFrameURI);

        // Add the report name component of the data record
        Text name = sweHelper.newText(sweHelper.getPropertyUri("ReportName"),
                "Report Name",
                "An identifier used as a describer for the report");

        // Add the report description component of the data record
        Text description = sweHelper.newText(sweHelper.getPropertyUri("ReportDescription"),
                "Report Description",
                "A verbose description of the observed event");

        // Add the reporting item component of the data record
        Text category = sweHelper.newText(sweHelper.getPropertyUri("ReportCategory"),
                "Report Category",
                "A categorical value used to identify a report as belonging to a kind, family, or group of reports");

        // Add image data block
        Boolean containsImage = sweHelper.newBoolean(SWEConstants.DEF_FLAG,
                "Image Flag",
                "A flag used to denote if the report has an associated image");


        DataRecord image = new VideoCamHelper().newVideoFrameRGB("image", imgWidth, imgHeight);


        dataStruct.addComponent(DATA_RECORD_LOC_LABEL, locationVectorLLA);
        dataStruct.addComponent(DATA_RECORD_REPORT_NAME_LABEL, name);
        dataStruct.addComponent(DATA_RECORD_REPORT_DESCRIPTION_LABEL, description);
        dataStruct.addComponent(DATA_RECORD_REPORTING_CATEGORY_LABEL, category);
        dataStruct.addComponent(DATA_RECORD_REPORTING_CONTAINS_IMAGE_LABEL, containsImage);
        dataStruct.addComponent(DATA_RECORD_REPORTING_IMAGE_LABEL, image);

        // Binary encoding for message data structure
        BinaryEncoding dataEncoding = sweHelper.newBinaryEncoding();
        dataEncoding.setByteEncoding(ByteEncoding.RAW);
        dataEncoding.setByteOrder(ByteOrder.BIG_ENDIAN);

        // Specify encoding for location field
//        BinaryComponent locEnc = sweHelper.newBinaryComponent();
//        locEnc.setRef("/" + locationVectorLLA.getName());
//        locEnc.setCdmDataType(DataType.DOUBLE);
//        dataEncoding.addMemberAsComponent(locEnc);

        // Specify encoding for name field
        BinaryComponent nameEnc = sweHelper.newBinaryComponent();
        nameEnc.setRef("/" + name.getName());
        nameEnc.setCdmDataType(DataType.ASCII_STRING);
        dataEncoding.addMemberAsComponent(nameEnc);

        // Specify encoding for description field
        BinaryComponent descriptionEnc = sweHelper.newBinaryComponent();
        descriptionEnc.setRef("/" + description.getName());
        descriptionEnc.setCdmDataType(DataType.ASCII_STRING);
        dataEncoding.addMemberAsComponent(descriptionEnc);

        // Specify encoding for category field
        BinaryComponent categoryEnc = sweHelper.newBinaryComponent();
        categoryEnc.setRef("/" + category.getName());
        categoryEnc.setCdmDataType(DataType.ASCII_STRING);
        dataEncoding.addMemberAsComponent(categoryEnc);

        // Specify encoding for image flag field
        BinaryComponent containsImageEnc = sweHelper.newBinaryComponent();
        containsImageEnc.setRef("/" + containsImage.getName());
        containsImageEnc.setCdmDataType(DataType.BOOLEAN);
        dataEncoding.addMemberAsComponent(containsImageEnc);

        // Specify encoding for image field
//        BinaryComponent imageEnc = sweHelper.newBinaryComponent();
//        imageEnc.setRef("/" + image.getName());
//        imageEnc.setCdmDataType(DataType.MIXED);
//        dataEncoding.addMemberAsComponent(imageEnc);

        try
        {
            SWEHelper.assignBinaryEncoding(dataStruct, dataEncoding);
        }
        catch (CDMException e)
        {
            throw new RuntimeException("Invalid binary encoding configuration", e);
        }

        this.dataEncoding = dataEncoding;
    }

    private boolean submitReport(String category, String locationSource, String name, String description) {

        Location location;

        location = getLocation(locationSource);

        double samplingTime = location.getTime() / 1000.0;

        // generate new data record
        DataBlock newRecord;
        if (latestRecord == null) {

            newRecord = dataStruct.createDataBlock();
        }
        else {

            newRecord = latestRecord.renew();
        }

        newRecord.setDoubleValue(0, location.getLatitude());
        newRecord.setDoubleValue(1, location.getLongitude());
        newRecord.setDoubleValue(2, location.getAltitude());
        newRecord.setStringValue(3, name);
        newRecord.setStringValue(4, description);
        newRecord.setStringValue(5, category);
        newRecord.setBooleanValue(6, false);

        // update latest record and send event
        latestRecord = newRecord;
        latestRecordTime = System.currentTimeMillis();
        eventHandler.publishEvent(new SensorDataEvent(latestRecordTime, this, newRecord));

        return true;
    }

    private boolean submitReport(String category, String locationSource, String name, String description, byte[] image) {

        Location location;

        location = getLocation(locationSource);

        double samplingTime = location.getTime() / 1000.0;

        // generate new data record
        DataBlock newRecord;
        if (latestRecord == null) {

            newRecord = dataStruct.createDataBlock();
        }
        else {

            newRecord = latestRecord.renew();
        }

        newRecord.setDoubleValue(0, location.getLatitude());
        newRecord.setDoubleValue(1, location.getLongitude());
        newRecord.setDoubleValue(2, location.getAltitude());
        newRecord.setStringValue(3, name);
        newRecord.setStringValue(4, description);
        newRecord.setStringValue(5, category);
        newRecord.setBooleanValue(6, true);
        newRecord.setDoubleValue(7, samplingTime);
        ((DataBlockMixed)newRecord).getUnderlyingObject()[8].setUnderlyingObject(image);

        // update latest record and send event
        latestRecord = newRecord;
        latestRecordTime = System.currentTimeMillis();
        eventHandler.publishEvent(new SensorDataEvent(latestRecordTime, this, newRecord));

        return true;
    }

    public void start(Context context) {

        this.context = context;
        context.registerReceiver(broadcastReceiver, new IntentFilter(ACTION_SUBMIT_REPORT));
    }
    
    @Override
    public void stop() {

        context.unregisterReceiver(broadcastReceiver);
    }

    @Override
    public double getAverageSamplingPeriod() {

        return 1;
    }

    @Override
    public DataComponent getRecordDescription() {

        return dataStruct;
    }

    @Override
    public DataEncoding getRecommendedEncoding() {

        return dataEncoding;
    }
    
    @Override
    public DataBlock getLatestRecord() {

        return latestRecord;
    }
    
    @Override
    public long getLatestRecordTime() {

        return latestRecordTime;
    }

    private Location getLocation(String locationSource) {

        Location location = null;

        SpotReportConfig config = getParentModule().getConfiguration();

        // Attempt to get location based given location source
        if (config.androidContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION)) {

            // Retrieve the location manager
            LocationManager locationManager = (LocationManager) config.androidContext.getSystemService(Context.LOCATION_SERVICE);

            // Get a list of all location providers
            List<String> locProviders = locationManager.getAllProviders();

            // Scan through the list until a provider is matched
            for (String providerName : locProviders) {

                if (providerName.equalsIgnoreCase(locationSource)) {

                    log.debug("Detected location provider " + providerName);

                    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                        location = locationManager.getLastKnownLocation(providerName);

                        log.debug("Location " + location.toString());
                    }
                }
            }
        }

        return location;
    }

    private class SpotReportReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {

            if(intent.getAction().equals(ACTION_SUBMIT_REPORT)) {

                String category = intent.getStringExtra(DATA_REPORT_CATEGORY);
                String locationSource = intent.getStringExtra(DATA_LOC);
                String name = intent.getStringExtra(DATA_REPORT_NAME);
                String description = intent.getStringExtra(DATA_REPORT_DESCRIPTION);
                String uriString = intent.getStringExtra(DATA_REPORT_IMAGE);
                ResultReceiver resultReceiver = intent.getParcelableExtra(Intent.EXTRA_RESULT_RECEIVER);

                imageBuffer.reset();
                try {

                    if(uriString != null) {

                        Uri imageUri = Uri.parse(uriString);
                        Bitmap imageBitmap = MediaStore.Images.Media.getBitmap(context.getContentResolver(), imageUri);
                        imageBitmap.compress(Bitmap.CompressFormat.JPEG, 90, imageBuffer);

                        submitReport(category, locationSource, name, description, imageBuffer.toByteArray());
                    }
                    else {

                        submitReport(category, locationSource, name, description);
                    }

                    resultReceiver.send(SUBMIT_REPORT_SUCCESS, null);

                } catch(Exception e) {

                    Log.e("SpotReportOutput", e.toString());
                    resultReceiver.send(SUBMIT_REPORT_FAILURE, null);
                }
            }
        }
    }
}
