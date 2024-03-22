//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//

package com.jpassing.examples;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.cloudresourcemanager.v3.CloudResourceManager;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AwsCredentialSource;
import com.google.auth.oauth2.AwsCredentials;
import com.google.auth.oauth2.ExternalAccountCredentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.util.Map;

/**
 * Example handler function that authenticates to Google Cloud
 * and performs an API request to verify that authentication worked.
 */
public class ExampleHandler implements RequestHandler<String, String>{

    public String handleRequest(String input, Context context)
    {
        var logger = context.getLogger();

        try
        {
            //
            // (1) Initialize a GoogleCredential.
            //
            // Option 1: Initialize credential configuration from an embedded resource
            //           or local file.
            //
            //           Credential configuration files aren't confidential, so it's
            //           ok to store them in source control, but any change requires
            //           a redeployment.
            //
            // var credentials = authenticateUsingEmbeddedCredentialConfiguration();

            //
            // Option 2: Initialize credential programmatically using data from
            //           environment variables.
            //
            var credentials = authenticateUsingEnvironment();

            //
            // The first time we use the credential, the library calls the
            // Google STS API to obtain an STS token and uses that to impersonate
            // a service account.
            //
            // For that to work, we must grant the Lambda function's IAM principal
            // permission to impersonate the service account.
            //
            // Assuming we use an attribute mapping 'google.subject=assertion.arn', the
            // IAM principal of the Lambda function looks similar to:
            //
            //   principal://iam.googleapis.com/projects/PROJECTNUMBER/locations/global/workloadIdentityPools/POOL \
            //   /subject/arn:aws:sts::ACCOUNTID:assumed-role/example-role-ABCDE/example
            //

            //
            // (2) For testing only: Log some details about the credential.
            //
            var accessToken = credentials.refreshAccessToken();
            logger.log(String.format(
              "Acquired Google access token %s... (expires %s)",
              accessToken.getTokenValue().substring(0, 15),
              accessToken.getExpirationTime()));

            //
            // (3) Initialize a client and make an API call. The library automatically
            //     performs access tokens refreshes as necessary.
            //
            // As an example, use the Resource Manager API to list all projects that
            // this principal can access.
            //
            var client = new CloudResourceManager
              .Builder(
                  GoogleNetHttpTransport.newTrustedTransport(),
                  new GsonFactory(),
                  new HttpCredentialsAdapter(credentials))
              .build();

            for (var project : client.projects().search().execute().getProjects())
            {
                logger.log(String.format("Accessible project: %s", project.getProjectId()));
            }
        }
        catch (Exception e)
        {
            var buffer = new StringBuilder();
            buffer.append("Authentication failed: ");

            for (Throwable cause = e; cause != null; cause = cause.getCause())
            {
                if (cause != e)
                {
                    buffer.append(", caused by: ");
                }

                logger.log(cause.getMessage());
            }

            logger.log(buffer.toString());

            throw new RuntimeException("Authentication failed, see logs for details");
        }

        return "Authentication successful, see logs for details";
    }

    private static ExternalAccountCredentials authenticateUsingEmbeddedCredentialConfiguration()
      throws IOException
    {
        try (var credentialConfigJson = ExampleHandler.class
          .getClassLoader()
          .getResourceAsStream("credential-configuration.json"))
        {
            return ExternalAccountCredentials.fromStream(credentialConfigJson);
        }
    }

    private static ExternalAccountCredentials authenticateUsingEnvironment()
    {
        var audience = System.getenv("GOOGLE_WORKLOADIDENTITY_AUDIENCE");
        var serviceAccount = System.getenv("GOOGLE_WORKLOADIDENTITY_SERVICEACCOUNT");
        var region = System.getenv("AWS_DEFAULT_REGION");

        Preconditions.checkNotNull(audience, "GOOGLE_WORKLOADIDENTITY_AUDIENCE must be set");
        Preconditions.checkNotNull(serviceAccount, "GOOGLE_WORKLOADIDENTITY_SERVICEACCOUNT must be set");

        return AwsCredentials.newBuilder()
          .setSubjectTokenType("urn:ietf:params:aws:token-type:aws4_request")
          .setTokenUrl("https://sts.googleapis.com/v1/token")
          .setAudience(audience)
          .setCredentialSource(new AwsCredentialSource(Map.of(
            "environment_id",
            "aws1",
            "regional_cred_verification_url",
            String.format("https://sts.%s.amazonaws.com?Action=GetCallerIdentity&Version=2011-06-15", region)
          )))
          .setServiceAccountImpersonationUrl(
            String.format(
              "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/%s:generateAccessToken",
              serviceAccount))
          .build();
    }
}
