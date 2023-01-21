package main.java.aws;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.*;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CloudWatchException;
import software.amazon.awssdk.services.cloudwatch.model.ComparisonOperator;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricAlarmRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.elasticloadbalancing.ElasticLoadBalancingClient;

public class AutoScalingScenario {

    private static String createLaunchTemplate(Ec2Client ec2, String templateName, String imgIdRest,
                                               String instType, String keyPairName, String secTomcatGroupId, String dbHost) {
        RequestLaunchTemplateData data = RequestLaunchTemplateData.builder().
                imageId(imgIdRest).instanceType(instType).
                keyName(keyPairName).securityGroupIds(secTomcatGroupId).
                userData(new String(LBScenario.getUserData(dbHost))).
                build();
        CreateLaunchTemplateRequest req = CreateLaunchTemplateRequest.builder().launchTemplateName(templateName).
                launchTemplateData(data).build();
        CreateLaunchTemplateResponse resp = ec2.createLaunchTemplate(req);
        if (resp != null) return resp.launchTemplate().launchTemplateId();
        else return null;
    }

    public static void deleteLaunchTemplate(Ec2Client ec2, String templateName) {
        DeleteLaunchTemplateRequest req = DeleteLaunchTemplateRequest.builder().launchTemplateName(templateName).
                build();
        ec2.deleteLaunchTemplate(req);
    }

    public static void createAutoScalingGroup(AutoScalingClient asc, String groupName,
                                              int desiredCapacity, int minCapacity, int maxCapacity, String lbName, String templateId) {
        LaunchTemplateSpecification spec = LaunchTemplateSpecification.builder().
                launchTemplateId(templateId).build();
        CreateAutoScalingGroupRequest req = CreateAutoScalingGroupRequest.builder().
                autoScalingGroupName(groupName).availabilityZones("us-east-1a").
                healthCheckType("ELB").loadBalancerNames(lbName).
                desiredCapacity(desiredCapacity).minSize(minCapacity).
                maxSize(maxCapacity).launchTemplate(spec).
                healthCheckGracePeriod(300).
                build();
        asc.createAutoScalingGroup(req);
    }

    public static void updateAutoScalingGroup(AutoScalingClient asc, String groupName, int desiredCapacity,
                                              int minCapacity, int maxCapacity) {
        UpdateAutoScalingGroupRequest req = UpdateAutoScalingGroupRequest.builder().
                autoScalingGroupName(groupName).desiredCapacity(desiredCapacity).minSize(minCapacity).
                maxSize(maxCapacity).build();
        asc.updateAutoScalingGroup(req);
    }

    public static String putScalingPolicy(AutoScalingClient asc, String scaleGroupName, String policyName,
                                          int adjustment) {
        PutScalingPolicyRequest req = PutScalingPolicyRequest.builder().
                autoScalingGroupName(scaleGroupName).adjustmentType("ChangeInCapacity").cooldown(120).
                policyName(policyName).policyType("SimpleScaling").scalingAdjustment(adjustment).
                build();
        PutScalingPolicyResponse resp = asc.putScalingPolicy(req);
        if (resp != null) return resp.policyARN();
        else return null;
    }


    public static Instance createDBInstance(Ec2Client ec2, String imgId, String instType, String keyPairName,
                                            String secGroupId) {
        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(imgId)
                .instanceType(instType)
                .maxCount(1)
                .minCount(1)
                .keyName(keyPairName)
                .securityGroupIds(secGroupId)
                .build();
        RunInstancesResponse response = ec2.runInstances(runRequest);

        List<Instance> insts = response.instances();
        if (insts != null && !insts.isEmpty()) {
            return insts.get(0);
        }

        return null;
    }

    public static String putStepScalingPolicy(AutoScalingClient asc,String scaleGroupName,String policyName)
    {
        StepAdjustment step1 = StepAdjustment.builder().metricIntervalLowerBound(0.0).metricIntervalUpperBound(10.0).scalingAdjustment(30).build();
        StepAdjustment step2 = StepAdjustment.builder().metricIntervalLowerBound(10.0).metricIntervalUpperBound(20.0).scalingAdjustment(60).build();

        PutScalingPolicyRequest request = PutScalingPolicyRequest.builder().autoScalingGroupName(scaleGroupName).
                adjustmentType("PercentChangeInCapacity").policyName(policyName).policyType("StepScaling").
                estimatedInstanceWarmup(120).minAdjustmentMagnitude(2).
                stepAdjustments(step1,step2).build();

        asc.putScalingPolicy(request);
        return scaleGroupName;
    }

    public static boolean putMetricAlarm(CloudWatchClient cw, String alarmName, String policyArn, String groupName,
                                      double threshold) {
        try {
            Dimension groupDimension = Dimension.builder()
                    .name("AutoScalingGroupName").value(groupName).build();
            PutMetricAlarmRequest request = PutMetricAlarmRequest.builder()
                    .alarmName(alarmName)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .evaluationPeriods(6).datapointsToAlarm(2).metricName("CPUUtilization")
                    .namespace("AWS/EC2").period(120)
                    .statistic(Statistic.AVERAGE).threshold(threshold)
                    .alarmActions(policyArn)
                    .alarmDescription(
                            "Alarm when server CPU utilization exceeds 80%")
                    .dimensions(groupDimension).build();
            cw.putMetricAlarm(request);
            System.out.printf(
                    "Successfully created alarm with name %s", alarmName);
            return true;
        } catch (CloudWatchException e) {
            System.err.println(e.awsErrorDetails().errorMessage());
            return false;
        }
    }

    public static void deleteAutoscalingGroup(AutoScalingClient asc, String groupName) {
        DeleteAutoScalingGroupRequest req = DeleteAutoScalingGroupRequest.builder().
                autoScalingGroupName(groupName).forceDelete(true).build();
        asc.deleteAutoScalingGroup(req);
    }

    public static void main(String[] args) {
        String lbName = "Atypon_test";
        String secTomcatGroupId = "sg-0ed92da58ca7a01e0";
        String secMySQLGroupId = "sg-07827259d5ac8bde3";
        String imgIdRest = "ami-00874d747dde814fa";
        String imgIdMySQL = "ami-0f0c03219214d761f";
        String instType = "t2.micro";
        String keyPairName = "atypon_keys";
        String dbHost;
        String groupName = "TargetGroup";
        String templateId;
        String templateName = "TargetGroupTemplate";
        String policyArn;
        String policyName = "TargetScale";

        //Create Ec2Client
        Ec2Client ec2 = Ec2Client.builder().region(Region.US_WEST_2).build();

        //Create dbInstance & retrieve dbHost IP
        Instance dbInstance = LBScenario.createDBInstance(ec2, imgIdMySQL, instType, keyPairName, secMySQLGroupId);
        if (dbInstance == null) {
            ec2.close();
            System.exit(-1);
        }
        dbHost = LBScenario.getIpWhenReady(ec2, dbInstance.instanceId());
        if (dbHost == null) {
            ec2.close();
            System.exit(-1);
        }
        //dbHost = "34.221.171.104";

        //Create Elastic load balancing client
        ElasticLoadBalancingClient elbc = ElasticLoadBalancingClient.builder().region(Region.US_WEST_2).build();

        //Create load balancer and register only the Book Rest Service instances
        LBScenario.createLoadBalancer(elbc, lbName, secTomcatGroupId);

        //Create launch template
        templateId = createLaunchTemplate(ec2, templateName, imgIdRest, instType, keyPairName, secTomcatGroupId, dbHost);
        //templateId = "lt-06cf93792611fcf32";

        //Create AutoScalingClient
        AutoScalingClient asc = AutoScalingClient.builder()
                .region(Region.US_WEST_2)
                .build();

        //Create DB instance
        Instance db = createDBInstance(ec2, imgIdMySQL, instType, keyPairName, secMySQLGroupId);
        if (db == null) {
            ec2.close();
            System.exit(-1);
        }

        //Create AutoScalingGroup
        createAutoScalingGroup(asc, groupName, 1, 2, 6, lbName, templateId);

        //Create Scaling Policy
        policyArn = putStepScalingPolicy(asc, groupName, policyName);

        //Create AmazonWatchClient
        CloudWatchClient awc = CloudWatchClient.builder()
                .region(Region.US_WEST_2)
                .build();


        //Create CPU Utilization alarm
        putMetricAlarm(awc, lbName, policyArn, groupName, 80);

        //Wait to get user signal that there should be deletion of everything already created
        //If user presses Crtl-C nothing will be deleted
        Scanner sc = new Scanner(System.in);
        try {
            while (sc.hasNextLine()) {
                String str = sc.nextLine();
                if (str.trim().equals("end")) {
                    deleteAutoscalingGroup(asc, groupName);
                    LBScenario.deleteLoadBalancer(elbc, lbName);
                    deleteLaunchTemplate(ec2, templateName);
                    LBScenario.deleteAllInstances(ec2, dbInstance.instanceId(), new ArrayList<String>());

                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            sc.close();
        }

        ec2.close();
        elbc.close();
        asc.close();
        awc.close();
    }
}
