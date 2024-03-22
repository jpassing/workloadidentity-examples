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

using Google.Apis.Auth.OAuth2;
using Google.Apis.CloudResourceManager.v1;
using Google.Apis.Services;

static GoogleCredential CreateWorkloadIdentityCredential()
{
    //
    // Read endpoint and XSRF header from the App Services environment.
    // Cf. https://learn.microsoft.com/en-us/azure/app-service/overview-managed-identity
    //
    var identityHeader = Environment.GetEnvironmentVariable("IDENTITY_HEADER");
    var identityEndpoint = Environment.GetEnvironmentVariable("IDENTITY_ENDPOINT");

    if (string.IsNullOrEmpty(identityHeader) || string.IsNullOrEmpty(identityEndpoint))
    {
        throw new InvalidOperationException(
            "The environment variables IDENTITY_HEADER and IDENTITY_ENDPOINT " +
            "have not been initialized. This indicates that the application is " +
            "not running on Azure App Services or that you haven't assigned " +
            "a managed identity to the application yet");
    }

    //
    // Read workload identity configuration from environment.
    //
    var audience = 
        Environment.GetEnvironmentVariable("GOOGLE_WORKLOADIDENTITY_AUDIENCE");
    if (string.IsNullOrEmpty(audience) || !audience.StartsWith("//"))
    {
        throw new InvalidOperationException(
            "The environment variable GOOGLE_WORKLOADIDENTITY_AUDIENCE " +
            "has not been initialized. The variable must contain a valid " +
            "workload identity pool provider URL (without https: prefix)");
    }

    var appId = Environment.GetEnvironmentVariable("GOOGLE_WORKLOADIDENTITY_APPID");
    if (string.IsNullOrEmpty(appId))
    {
        throw new InvalidOperationException(
            "The environment variable GOOGLE_WORKLOADIDENTITY_APPID " +
            "has not been initialized. The variable must contain the App ID URI " +
            "of the Entra ID application registration trusted by your workload " +
            "identity pool provider");
    }

    var serviceAccountToImpersonate = 
        Environment.GetEnvironmentVariable("GOOGLE_WORKLOADIDENTITY_SERVICEACCOUNT");

    var identityEndpointRequestUrl = new UriBuilder(identityEndpoint)
    {
        Query = new QueryString()
            .Add("resource", appId)
            .Add("api-version", "2019-08-01")
            .ToString()
    }.Uri;

    //
    // Create the equivalent of a credential configuration file that
    // incorporates the identity endpoint request URL and header.
    //
    var credentialConfiguration = new JsonCredentialParameters
    {
        Type = "external_account",
        Audience = audience,
        SubjectTokenType = "urn:ietf:params:oauth:token-type:jwt",
        ServiceAccountImpersonationUrl = serviceAccountToImpersonate == null 
            ? null  // No impersonation
            : "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/" +
                $"{serviceAccountToImpersonate}:generateAccessToken",
        TokenUrl = "https://sts.googleapis.com/v1/token",
        CredentialSourceConfig = new JsonCredentialParameters.CredentialSource
        {
            Url = identityEndpointRequestUrl.AbsoluteUri,
            Headers = new Dictionary<string, string>
                {
                    { "X-IDENTITY-HEADER", identityHeader }
                },
            Format = new JsonCredentialParameters.CredentialSource.SubjectTokenFormat
            {
                SubjectTokenFieldName = "access_token",
                Type = "json"
            }
        }
    };

    //
    // Create a workload identity federation credential that automatically
    // fetches and refreshes access tokens as necessary.
    //
    return GoogleCredential
        .FromJsonParameters(credentialConfiguration)
        .CreateScoped("https://www.googleapis.com/auth/cloud-platform");
}


var app = WebApplication
    .CreateBuilder(args)
    .Build();

app.UseHttpsRedirection();
app.MapGet("/", async () =>
{
    var credential = CreateWorkloadIdentityCredential();

    //
    // Make some API call using the credential.
    //
    var service = new CloudResourceManagerService(new BaseClientService.Initializer()
    {
        HttpClientInitializer = credential
    });

    var response = await service
        .Projects
        .List()
        .ExecuteAsync()
        .ConfigureAwait(false);

    return new
    {
        Projects = response.Projects
    };
});

app.Run();
