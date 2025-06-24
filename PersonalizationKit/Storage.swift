//
//  QuestionnaireStorage.swift
//  PersonalizationKit
//
//  Created by Daniya on 28/06/2022.
//

import Foundation

public protocol LearnerStorage {
    
    var serverUrl: String {get}
    var learnerCollectionName: String {get}
    var activtyLogCollectionName: String {get}
    var currentAppVersion: String? {get}
    var currentSessionNumber: Int? {get}
    
    func store(_ anyObject: Any, forKey key: String)
    
    func retrieve(forKey key: String) -> Any?
    
    func remove(forKey key: String)
    
    func getAllItemKeys(withPrefix: String) -> [String]
    
    func localizedString(forKey key: String) -> String
        
}


public class StorageDelegate {
        
    public static var learnerStorage: LearnerStorage!
    
}
