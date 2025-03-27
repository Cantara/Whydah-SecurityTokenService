package net.whydah.sts.user;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by totto on 10/2/14.
 */
public class UserAggregateMappingTest {

    private final static DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    private Map applicationCompanyRoleValueMap = new HashMap();

    public void testMultiRoleApplications() {

        String userAggregateXML = """
                <whydahuser>
                    <identity>
                        <username>Jan.Helge.Maurtvedt@altran.com</username>
                        <cellPhone></cellPhone>
                        <email>Jan.Helge.Maurtvedt@altran.com</email>
                        <firstname>Jan Helge</firstname>
                        <lastname>Maurtvedt</lastname>
                        <personRef></personRef>
                        <UID>19dc76a8-b122-4138-b08f-7367e9988c06</UID>
                    </identity>
                    <applications>
                        <application>
                            <appId>99</appId>
                            <applicationName>WhydahTestWebApplication</applicationName>
                            <orgName>Whydah</orgName>
                            <roleName>WhydahDefaultUser</roleName>
                            <roleValue>true</roleValue>
                        </application>
                        <application>
                            <appId>100</appId>
                            <applicationName>ACS</applicationName>
                            <orgName>Altran</orgName>
                            <roleName>Employee</roleName>
                            <roleValue>Jan.Helge.Maurtvedt@altran.com</roleValue>
                        </application>
                        <application>
                            <appId>100</appId>
                            <applicationName>ACS</applicationName>
                            <orgName>Altran</orgName>
                            <roleName>Manager</roleName>
                            <roleValue></roleValue>
                        </application>
                        <application>
                            <appId>99</appId>
                            <applicationName>WhydahTestWebApplication</applicationName>
                            <orgName>Whydah</orgName>
                            <roleName>WhydahDefaultUser</roleName>
                            <roleValue>Jan.Helge.Maurtvedt@altran.com</roleValue>
                        </application>
                    </applications>
                </whydahuser>
                """;


    }


}
